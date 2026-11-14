package dev.ghostlay.demo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

public class OverlayDrawView extends View {
    /** Test box: filled, 100x100 pixels, pinned to the top-right corner. */
    private static final int TEST_BOX = 100;
    private static final int TEST_BOX_MARGIN = 40;

    private boolean lineEnabled = false;
    private boolean boxEnabled = false;
    private String linePosition = "Bottom"; // Top, Middle, Bottom
    private String boxStyle = "Filled";
    private int color = 0xFFFF3B30;          // default player red
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);

    public OverlayDrawView(Context context) {
        super(context);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        fill.setStyle(Paint.Style.FILL);
        setBackgroundColor(Color.TRANSPARENT);
    }

    public void update(boolean lineEnabled, boolean boxEnabled, String linePosition, String boxStyle, String colorHex) {
        this.lineEnabled = lineEnabled;
        this.boxEnabled = boxEnabled;
        if (linePosition != null) this.linePosition = linePosition;
        if (boxStyle != null) this.boxStyle = boxStyle;
        this.color = parseColor(colorHex, 0xFFFF3B30);
        invalidate();
    }

    public boolean hasAnythingToDraw() {
        return lineEnabled || boxEnabled;
    }

    private int parseColor(String hex, int def) {
        if (hex == null) return def;
        try {
            return Color.parseColor(hex);
        } catch (Exception e) {
            return def;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!lineEnabled && !boxEnabled) return;

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        if (boxEnabled) {
            // Filled 100x100 test box, top-right corner.
            float right = w - TEST_BOX_MARGIN;
            float top = TEST_BOX_MARGIN;
            float left = Math.max(0f, right - TEST_BOX);
            float bottom = top + TEST_BOX;

            fill.setColor(color);
            fill.setAlpha(230);
            canvas.drawRect(left, top, right, bottom, fill);

            // Thin outline so the box stays visible on same-colored backgrounds.
            stroke.setColor(0xFFFFFFFF);
            stroke.setStrokeWidth(4f);
            canvas.drawRect(left, top, right, bottom, stroke);
        }

        if (lineEnabled) {
            float y;
            if ("Top".equalsIgnoreCase(linePosition)) y = h * 0.22f;
            else if ("Middle".equalsIgnoreCase(linePosition)) y = h * 0.50f;
            else y = h * 0.78f; // Bottom
            stroke.setColor(color);
            stroke.setStrokeWidth(Math.max(4f, w / 180f));
            canvas.drawLine(w * 0.20f, y, w * 0.80f, y, stroke);
        }
    }
}
