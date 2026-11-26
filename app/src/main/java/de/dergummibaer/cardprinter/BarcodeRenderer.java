package de.dergummibaer.cardprinter;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;

/**
 * Renders a Code 128 barcode to a monochrome bitmap sized for the printer head.
 * Black bars on a white background. The bars run across the width (the 384-dot
 * print-head axis), so on a 50x30 mm label they are tall and easy to scan.
 */
public final class BarcodeRenderer {

    public static final int QUIET = 10;   // blank modules each side (scanner requirement)

    private BarcodeRenderer() {}

    /** Narrowest bar width in dots for this code at this image width. <2 is too thin to scan. */
    public static int moduleWidthPx(String text, int widthDots) {
        boolean[] m = Code128.encode(text);
        if (m == null) return 0;
        return Math.max(1, widthDots / (m.length + 2 * QUIET));
    }

    /**
     * @param text       the string to encode (e.g. the card UID)
     * @param widthDots  image width = print-head width (384 for the B1)
     * @param heightDots image height = label length in dots (240 for a 30 mm label)
     */
    public static Bitmap render(String text, int widthDots, int heightDots) {
        return render(text, text, widthDots, heightDots);
    }

    /** encodeText goes into the bars; displayText is the human-readable line underneath. */
    public static Bitmap render(String encodeText, String displayText, int widthDots, int heightDots) {
        boolean[] modules = Code128.encode(encodeText);
        if (modules == null) throw new IllegalArgumentException("Cannot encode: " + encodeText);
        int n = modules.length;

        Bitmap bmp = Bitmap.createBitmap(widthDots, heightDots, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.WHITE);

        int px = Math.max(1, widthDots / (n + 2 * QUIET));
        int barcodeWidth = n * px;
        int x0 = (widthDots - barcodeWidth) / 2;

        // Reserve the bottom strip for the human-readable number.
        int textArea = Math.max(28, heightDots / 6);
        int barTop = Math.max(6, heightDots / 16);
        int barBottom = heightDots - textArea;

        Paint bar = new Paint();
        bar.setColor(Color.BLACK);
        bar.setAntiAlias(false);
        for (int i = 0; i < n; ) {
            if (!modules[i]) { i++; continue; }
            int j = i;
            while (j < n && modules[j]) j++;
            c.drawRect(x0 + i * px, barTop, x0 + j * px, barBottom, bar);
            i = j;
        }

        Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        label.setColor(Color.BLACK);
        label.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        // Fit the text to the width.
        float size = textArea * 0.8f;
        label.setTextSize(size);
        Rect b = new Rect();
        label.getTextBounds(displayText, 0, displayText.length(), b);
        if (b.width() > widthDots - 16) {
            size = size * (widthDots - 16) / b.width();
            label.setTextSize(size);
            label.getTextBounds(displayText, 0, displayText.length(), b);
        }
        float tx = (widthDots - b.width()) / 2f - b.left;
        float ty = barBottom + (textArea + b.height()) / 2f;
        c.drawText(displayText, tx, ty, label);

        return bmp;
    }
}
