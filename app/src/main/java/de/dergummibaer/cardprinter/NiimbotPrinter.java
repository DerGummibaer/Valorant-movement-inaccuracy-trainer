package de.dergummibaer.cardprinter;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.graphics.Bitmap;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Talks to a Niimbot B1 over classic Bluetooth (SPP / RFCOMM).
 * Protocol ported from the open-source niimprint project (tested on the B1).
 * Frame: 55 55 <type> <len> <data...> <checksum> AA AA, checksum = type ^ len ^ data.
 *
 * All methods block, so call them off the UI thread.
 */
public class NiimbotPrinter {

    public interface Log { void line(String s); }

    private static final UUID SPP =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // Request command codes
    private static final int SET_DENSITY     = 0x21;
    private static final int SET_LABEL_TYPE  = 0x23;
    private static final int START_PRINT     = 0x01;
    private static final int END_PRINT       = 0xF3;
    private static final int START_PAGE      = 0x03;
    private static final int END_PAGE        = 0xE3;
    private static final int SET_DIMENSION   = 0x13;
    private static final int IMAGE_ROW       = 0x85;
    private static final int EMPTY_ROW       = 0x84;
    private static final int PRINT_STATUS    = 0xA3;

    private final Log log;
    private BluetoothSocket socket;
    private InputStream in;
    private OutputStream out;

    public NiimbotPrinter(Log log) { this.log = log; }

    private void log(String s) { if (log != null) log.line(s); }

    public boolean isConnected() { return socket != null && socket.isConnected(); }

    /** Opens an SPP connection to the printer. Throws on failure. */
    public void connect(BluetoothDevice device) throws IOException {
        close();
        IOException first = null;
        try {
            socket = device.createRfcommSocketToServiceRecord(SPP);
            socket.connect();
        } catch (IOException e) {
            first = e;
            log("SPP connect failed, trying fallback channel 1...");
            socket = fallbackSocket(device);
            if (socket == null) throw first;
            socket.connect();
        }
        in = socket.getInputStream();
        out = socket.getOutputStream();
        log("Connected to " + safeName(device));
    }

    private BluetoothSocket fallbackSocket(BluetoothDevice device) {
        try {
            Method m = device.getClass().getMethod("createRfcommSocket", int.class);
            return (BluetoothSocket) m.invoke(device, 1);
        } catch (Exception e) {
            return null;
        }
    }

    private String safeName(BluetoothDevice d) {
        try { return d.getName() != null ? d.getName() : d.getAddress(); }
        catch (SecurityException e) { return d.getAddress(); }
    }

    public void close() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        socket = null; in = null; out = null;
    }

    // ---- framing ----

    private static byte[] frame(int type, byte[] data) {
        int chk = type ^ data.length;
        for (byte b : data) chk ^= (b & 0xFF);
        byte[] out = new byte[data.length + 7];
        out[0] = 0x55; out[1] = 0x55;
        out[2] = (byte) type;
        out[3] = (byte) data.length;
        System.arraycopy(data, 0, out, 4, data.length);
        out[4 + data.length] = (byte) chk;
        out[5 + data.length] = (byte) 0xAA;
        out[6 + data.length] = (byte) 0xAA;
        return out;
    }

    private void send(int type, byte[] data) throws IOException {
        out.write(frame(type, data));
        out.flush();
    }

    /** Sends a command and waits up to timeoutMs for the matching ack packet. */
    private byte[] transceive(int reqType, byte[] data, int respType, int timeoutMs)
            throws IOException {
        send(reqType, data);
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            byte[][] pkt = readPacket(end - System.currentTimeMillis());
            if (pkt == null) break;
            int type = pkt[0][0] & 0xFF;
            if (type == respType) return pkt[1];
            // ignore anything else (status/heartbeat noise)
        }
        return null;
    }

    /**
     * Reads one framed packet. Returns {{type}, data} or null on timeout.
     * Tolerates fragmentation across reads.
     */
    private final byte[] buf = new byte[4096];
    private int bufLen = 0;

    private byte[][] readPacket(long timeoutMs) throws IOException {
        long end = System.currentTimeMillis() + Math.max(1, timeoutMs);
        while (true) {
            // Try to parse a complete packet from the buffer.
            int start = -1;
            for (int i = 0; i + 1 < bufLen; i++) {
                if ((buf[i] & 0xFF) == 0x55 && (buf[i + 1] & 0xFF) == 0x55) { start = i; break; }
            }
            if (start >= 0 && bufLen >= start + 4) {
                int len = buf[start + 3] & 0xFF;
                int total = len + 7;
                if (bufLen >= start + total) {
                    int type = buf[start + 2] & 0xFF;
                    byte[] data = new byte[len];
                    System.arraycopy(buf, start + 4, data, 0, len);
                    // consume up to end of this packet
                    int consumed = start + total;
                    System.arraycopy(buf, consumed, buf, 0, bufLen - consumed);
                    bufLen -= consumed;
                    return new byte[][] { new byte[] { (byte) type }, data };
                }
            }
            if (System.currentTimeMillis() >= end) return null;
            if (in.available() > 0) {
                int r = in.read(buf, bufLen, buf.length - bufLen);
                if (r > 0) bufLen += r;
            } else {
                try { Thread.sleep(15); } catch (InterruptedException ignored) {}
            }
        }
    }

    // ---- high level ----

    /**
     * Prints a bitmap using the B1 print sequence (ported from niimbluelib's B1PrintTask).
     * Black pixels burn. density 1..5.
     */
    public void printImage(Bitmap bmp, int density) throws IOException {
        int w = bmp.getWidth(), h = bmp.getHeight();
        log("Printing " + w + "x" + h + " at density " + density);

        ack("density", transceive(SET_DENSITY, new byte[]{(byte) density}, 0x31, 1500));
        ack("labelType", transceive(SET_LABEL_TYPE, new byte[]{1}, 0x33, 1500));  // 1 = labels with gaps
        // PrintStart, 7 bytes: total pages (u16), 4 zero bytes, page colour (0 = single colour)
        ack("printStart", transceive(START_PRINT, new byte[]{0, 1, 0, 0, 0, 0, 0}, 0x02, 2000));
        ack("pageStart", transceive(START_PAGE, new byte[]{1}, 0x04, 2000));
        // SetPageSize, 6 bytes: rows, cols, copies (all u16 big-endian)
        byte[] size = {(byte)(h >> 8), (byte) h, (byte)(w >> 8), (byte) w, 0, 1};
        ack("pageSize", transceive(SET_DIMENSION, size, 0x14, 2000));

        int rowBytes = (w + 7) / 8;
        int chunk = Math.max(1, rowBytes / 3);        // 384 dots -> 48 bytes -> 3 chunks of 16
        int[] px = new int[w];
        byte[] prev = null;
        int prevStart = 0, repeat = 0;
        for (int y = 0; y <= h; y++) {
            byte[] row = null;
            if (y < h) {
                row = new byte[rowBytes];
                bmp.getPixels(px, 0, w, 0, y, w, 1);
                for (int x = 0; x < w; x++) {
                    int p = px[x];
                    int lum = (p >> 16 & 0xFF) + (p >> 8 & 0xFF) + (p & 0xFF);
                    if (lum < 384) row[x >> 3] |= (byte) (0x80 >> (x & 7));
                }
            }
            // Merge runs of identical rows into one packet (max 255 repeats).
            if (prev != null && row != null && repeat < 255 && java.util.Arrays.equals(prev, row)) {
                repeat++;
                continue;
            }
            if (prev != null) sendRow(prev, prevStart, repeat, chunk);
            prev = row; prevStart = y; repeat = 1;
        }

        ack("pageEnd", transceive(END_PAGE, new byte[]{1}, 0xE4, 5000));

        // Poll print status until the page counter reaches 1.
        long until = System.currentTimeMillis() + 15000;
        boolean done = false;
        while (System.currentTimeMillis() < until) {
            byte[] st = transceive(PRINT_STATUS, new byte[]{1}, 0xB3, 1000);
            if (st != null && st.length >= 2 && (((st[0] & 0xFF) << 8) | (st[1] & 0xFF)) >= 1) {
                done = true; break;
            }
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        }
        transceive(END_PRINT, new byte[]{1}, 0xF4, 2000);
        log(done ? "Print finished." : "Print sent, but the printer never reported the page as done.");
    }

    /** Sends one image row (or an empty-row packet if it has no black pixels). */
    private void sendRow(byte[] row, int y, int repeat, int chunk) throws IOException {
        int[] counts = new int[3];
        int total = 0;
        for (int i = 0; i < row.length; i++) {
            int bits = Integer.bitCount(row[i] & 0xFF);
            total += bits;
            counts[Math.min(2, i / chunk)] += bits;
        }
        if (total == 0) {
            send(EMPTY_ROW, new byte[]{(byte)(y >> 8), (byte) y, (byte) repeat});
            return;
        }
        byte[] data = new byte[6 + row.length];
        data[0] = (byte)(y >> 8);
        data[1] = (byte) y;
        data[2] = (byte) counts[0];
        data[3] = (byte) counts[1];
        data[4] = (byte) counts[2];
        data[5] = (byte) repeat;
        System.arraycopy(row, 0, data, 6, row.length);
        send(IMAGE_ROW, data);
    }

    private void ack(String what, byte[] resp) {
        if (resp == null) log("  " + what + ": no ack (continuing)");
    }
}
