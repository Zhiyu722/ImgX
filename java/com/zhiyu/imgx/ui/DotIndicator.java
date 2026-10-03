package com.zhiyu.imgx.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** 页码指示器: 玻璃胶囊, 当前页为蓝色加长胶囊。 */
public class DotIndicator extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int count = 3;
    private float position;    // 连续位置, 用于平滑过渡

    public DotIndicator(Context context) {
        super(context);
    }

    public void setCount(int c) {
        count = c;
        invalidate();
    }

    public void setPosition(float pos) {
        position = pos;
        invalidate();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onMeasure(int w, int h) {
        int n = Math.max(1, count);
        setMeasuredDimension((int) (dp(10) * n + dp(10) * (n - 1) + dp(20)), (int) dp(12));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int n = Math.max(1, count);
        float d = dp(10);
        float gap = dp(10);
        float cx = getWidth() / 2f - ((n - 1) * (d + gap)) / 2f;
        float cy = getHeight() / 2f;
        for (int i = 0; i < n; i++) {
            float x = cx + i * (d + gap);
            float alpha = 1f - Math.min(1f, Math.abs(position - i));
            paint.setColor(Color.argb((int) (120 + 100 * alpha), 0xFF, 0xFF, 0xFF));
            canvas.drawCircle(x, cy, d / 2f, paint);
        }
        // 活动胶囊
        float activeX = cx + position * (d + gap);
        float w = dp(26);
        float a = Math.min(1f, 1f - Math.abs(position - Math.round(position)));
        rect.set(activeX - w / 2f, cy - d / 2f, activeX + w / 2f, cy + d / 2f);
        paint.setColor(Color.argb(255, 0x16, 0x9A, 0xFF));
        canvas.drawRoundRect(rect, d / 2f, d / 2f, paint);
    }
}
