package dev.ghostlay.demo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Simple center box shown when ESP box is enabled (same idea as Hello World overlay). */
public class EspBoxView extends View {
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);

    public EspBoxView(Context context) {
        super(context);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(4));
        stroke.setColor(0xFFFF3B30);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0x66FF3B30);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int size = (int) dp(120);
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float pad = dp(4);
        float left = pad;
        float top = pad;
        float right = getWidth() - pad;
        float bottom = getHeight() - pad;
        canvas.drawRect(left, top, right, bottom, fill);
        canvas.drawRect(left, top, right, bottom, stroke);
    }
}
