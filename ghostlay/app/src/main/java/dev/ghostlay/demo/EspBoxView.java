package dev.ghostlay.demo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Draws a simple box in the center of the screen when ESP box is enabled. */
public class EspBoxView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public EspBoxView(Context context) {
        super(context);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(6f);
        paint.setColor(0xFFFF3B30); // red, matches default player color
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float boxW = w * 0.28f;
        float boxH = h * 0.32f;
        float left = (w - boxW) / 2f;
        float top = (h - boxH) / 2f;
        float right = left + boxW;
        float bottom = top + boxH;

        canvas.drawRect(left, top, right, bottom, paint);
    }
}
