package de.dergummibaer.cardprinter;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tap an NFC card -> show its UID as a Code 128 barcode -> print it on the Niimbot B1.
 * Everything runs on the phone. Pair the B1 in Android's Bluetooth settings first.
 */
public class MainActivity extends Activity implements NfcAdapter.ReaderCallback {

    // ---- configuration ----
    private static final int LABEL_W = 384;   // print-head width in dots (B1)
    private static final int LABEL_H = 240;   // 30 mm label at 203 dpi
    private int density = 3;                  // 1..5, thermal darkness
    private int suffix = 2;                    // appended to the barcode: 0 none, 1 Tab, 2 Enter (card readers send Enter)

    private static final int COBALT = Color.parseColor("#1240E0");
    private static final int MUTE = Color.parseColor("#4A5470");
    private static final int REQ_BT = 1;

    private NfcAdapter nfc;
    private BluetoothAdapter bt;
    private final NiimbotPrinter printer = new NiimbotPrinter(this::log);
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private TextView status, logView, densityLabel;
    private Button suffixBtn;
    private ImageView preview;
    private CheckBox autoPrint;
    private Bitmap current;
    private BluetoothDevice lastDevice;
    private String currentText = "";

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private Button button(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(primary ? Color.WHITE : COBALT);
        if (primary) b.setBackgroundColor(COBALT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        b.setLayoutParams(lp);
        return b;
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("cardprinter", MODE_PRIVATE);
        density = prefs.getInt("density", 3);
        suffix = prefs.getInt("suffix", 2);
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bt = bm != null ? bm.getAdapter() : BluetoothAdapter.getDefaultAdapter();
        getWindow().setStatusBarColor(COBALT);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#EEF2FF"));

        TextView title = new TextView(this);
        title.setText("Card Printer");
        title.setTextColor(Color.WHITE);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setBackgroundColor(COBALT);
        title.setPadding(dp(18), dp(16), dp(18), dp(16));
        root.addView(title);

        status = new TextView(this);
        status.setTextColor(MUTE);
        status.setTextSize(15);
        status.setPadding(dp(16), dp(12), dp(16), dp(8));
        status.setText("Tap a card on the back of the phone.");
        root.addView(status);

        preview = new ImageView(this);
        preview.setBackgroundColor(Color.WHITE);
        preview.setAdjustViewBounds(true);
        preview.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(150));
        pl.setMargins(dp(16), dp(4), dp(16), dp(8));
        preview.setLayoutParams(pl);
        root.addView(preview);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setPadding(dp(12), 0, dp(12), 0);
        Button printerBtn = button("Printer", false);
        printerBtn.setOnClickListener(v -> choosePrinter());
        Button printBtn = button("Print", true);
        printBtn.setOnClickListener(v -> printCurrent());
        row1.addView(printerBtn);
        row1.addView(printBtn);
        root.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        row2.setPadding(dp(16), 0, dp(16), dp(4));
        autoPrint = new CheckBox(this);
        autoPrint.setText("Auto-print on scan");
        autoPrint.setChecked(prefs.getBoolean("auto", true));
        autoPrint.setOnCheckedChangeListener((b, c) ->
                prefs.edit().putBoolean("auto", c).apply());
        LinearLayout.LayoutParams apl = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        autoPrint.setLayoutParams(apl);
        row2.addView(autoPrint);

        Button dMinus = new Button(this);
        dMinus.setText("−");
        dMinus.setOnClickListener(v -> setDensity(density - 1));
        densityLabel = new TextView(this);
        densityLabel.setTextColor(MUTE);
        densityLabel.setPadding(dp(8), 0, dp(8), 0);
        Button dPlus = new Button(this);
        dPlus.setText("+");
        dPlus.setOnClickListener(v -> setDensity(density + 1));
        row2.addView(dMinus);
        row2.addView(densityLabel);
        row2.addView(dPlus);
        root.addView(row2);
        updateDensityLabel();

        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        row3.setGravity(Gravity.CENTER_VERTICAL);
        row3.setPadding(dp(16), 0, dp(16), dp(4));
        TextView suffixCap = new TextView(this);
        suffixCap.setText("After the code: ");
        suffixCap.setTextColor(MUTE);
        row3.addView(suffixCap);
        suffixBtn = new Button(this);
        suffixBtn.setAllCaps(false);
        suffixBtn.setOnClickListener(v -> cycleSuffix());
        row3.addView(suffixBtn);
        root.addView(row3);
        updateSuffixLabel();

        ScrollView sv = new ScrollView(this);
        LinearLayout.LayoutParams svl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        sv.setLayoutParams(svl);
        logView = new TextView(this);
        logView.setTextColor(MUTE);
        logView.setTextSize(12);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setPadding(dp(16), dp(8), dp(16), dp(16));
        sv.addView(logView);
        root.addView(sv);

        setContentView(root);

        if (bt == null) status.setText("This phone has no Bluetooth.");
        ensureBtPermission();
        autoConnect();
    }

    private void setDensity(int d) {
        density = Math.max(1, Math.min(5, d));
        prefs.edit().putInt("density", density).apply();
        updateDensityLabel();
    }

    private void updateDensityLabel() {
        densityLabel.setText("Density " + density);
    }

    // ---- NFC ----

    @Override
    protected void onResume() {
        super.onResume();
        nfc = NfcAdapter.getDefaultAdapter(this);
        if (nfc == null) { status.setText("This phone has no NFC reader."); return; }
        if (!nfc.isEnabled()) { status.setText("NFC is switched off in system settings."); return; }
        int flags = NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B
                | NfcAdapter.FLAG_READER_NFC_F | NfcAdapter.FLAG_READER_NFC_V
                | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK;
        nfc.enableReaderMode(this, this, flags, null);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (nfc != null) nfc.disableReaderMode(this);
    }

    @Override
    public void onTagDiscovered(Tag tag) {
        byte[] id = tag.getId();
        if (id == null || id.length == 0) return;
        StringBuilder sb = new StringBuilder();
        for (byte b : id) sb.append(String.format("%02X", b & 0xFF));
        final String hex = sb.toString();
        ui.post(() -> showCard(hex));
    }

    private void showCard(String text) {
        currentText = text;
        renderPreview();
        log("Scanned " + text);
        if (autoPrint.isChecked()) printCurrent();
    }

    /** Re-renders the preview bitmap from the last card + current suffix (no printing). */
    private void renderPreview() {
        if (currentText.isEmpty()) return;
        String enc = currentText + suffixChars();
        current = BarcodeRenderer.render(enc, currentText, LABEL_W, LABEL_H);
        preview.setImageBitmap(current);
        int px = BarcodeRenderer.moduleWidthPx(enc, LABEL_W);
        status.setText("Card " + currentText + (px < 2 ? "  (long code — bars are thin)" : ""));
    }

    private void cycleSuffix() {
        suffix = (suffix + 1) % 3;
        prefs.edit().putInt("suffix", suffix).apply();
        updateSuffixLabel();
        renderPreview();   // show the change without reprinting
    }

    private void updateSuffixLabel() {
        String[] names = {"None", "Tab ⇥", "Enter ⏎"};
        suffixBtn.setText(names[suffix]);
    }

    /** The characters appended to the barcode data (not shown in the printed number). */
    private String suffixChars() {
        return suffix == 1 ? "\t" : suffix == 2 ? "\r" : "";
    }

    // ---- printing ----

    private void printCurrent() {
        if (current == null) { status.setText("Tap a card first."); return; }
        if (!printer.isConnected()) {
            status.setText("No printer connected — tap Printer.");
            choosePrinter();
            return;
        }
        final Bitmap bmp = current;
        final int d = density;
        io.execute(() -> {
            try {
                printer.printImage(bmp, d);
            } catch (java.io.IOException e) {
                // The link drops when the printer sleeps; reconnect once and retry.
                log("Connection lost (" + e.getMessage() + "), reconnecting...");
                printer.close();
                if (lastDevice == null) { log("No printer to reconnect to."); return; }
                try {
                    printer.connect(lastDevice);
                    printer.printImage(bmp, d);
                } catch (Exception e2) {
                    log("Print error: " + e2.getMessage());
                    ui.post(() -> status.setText("Printer not reachable \u2014 is it on?"));
                }
            } catch (Exception e) {
                log("Print error: " + e.getMessage());
            }
        });
    }

    // ---- printer connection ----

    private boolean hasBtPermission() {
        if (Build.VERSION.SDK_INT < 31) return true;
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void ensureBtPermission() {
        if (!hasBtPermission()) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        super.onRequestPermissionsResult(req, p, r);
        if (req == REQ_BT && r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) {
            autoConnect();
        }
    }

    private void autoConnect() {
        String addr = prefs.getString("printer", null);
        if (addr == null || bt == null || !hasBtPermission()) return;
        try {
            BluetoothDevice d = bt.getRemoteDevice(addr);
            connect(d);
        } catch (IllegalArgumentException ignored) {}
    }

    private void choosePrinter() {
        if (bt == null) { status.setText("No Bluetooth on this phone."); return; }
        if (!bt.isEnabled()) { status.setText("Turn on Bluetooth first."); return; }
        if (!hasBtPermission()) { ensureBtPermission(); return; }

        List<BluetoothDevice> devices = new ArrayList<>();
        List<String> names = new ArrayList<>();
        Set<BluetoothDevice> bonded;
        try { bonded = bt.getBondedDevices(); }
        catch (SecurityException e) { ensureBtPermission(); return; }
        for (BluetoothDevice d : bonded) {
            String n;
            try { n = d.getName(); } catch (SecurityException e) { n = null; }
            devices.add(d);
            names.add((n != null ? n : "(unknown)") + "\n" + d.getAddress());
        }
        if (devices.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("No paired devices")
                    .setMessage("Pair the Niimbot B1 in Android's Bluetooth settings first, then come back.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Choose the B1")
                .setItems(names.toArray(new String[0]), (dlg, which) -> {
                    BluetoothDevice d = devices.get(which);
                    prefs.edit().putString("printer", d.getAddress()).apply();
                    connect(d);
                })
                .show();
    }

    private void connect(BluetoothDevice d) {
        lastDevice = d;
        status.setText("Connecting to printer...");
        io.execute(() -> {
            try {
                printer.connect(d);
                ui.post(() -> status.setText("Printer ready."));
            } catch (Exception e) {
                log("Connect failed: " + e.getMessage());
                ui.post(() -> status.setText("Could not connect to the printer."));
            }
        });
    }

    // ---- logging ----

    private void log(String s) {
        ui.post(() -> {
            logView.append(s + "\n");
            // keep the log from growing without bound
            if (logView.length() > 6000) {
                logView.setText(logView.getText().subSequence(logView.length() - 4000, logView.length()));
            }
        });
    }
}
