package com.zhiyu.imgx.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** 玻璃开关(解包页的「自动解包分区」开关)。 */
public class GlassSwitch extends View {

    private boolean checked = true;
    private float anim = checked ? 1f : 0f;   // 0=关 1=开
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private OnCheckedChangeListener listener;

    public interface OnCheckedChangeListener {
        void onChanged(boolean checked);
    }

    public GlassSwitch(Context context) {
        super(context);
        anim = checked ? 1f : 0f;
        thumb.setColor(Color.WHITE);
        thumb.setShadowLayer(dp(4), 0, dp(2), 0x40000000);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setOnClickListener(v -> setChecked(!checked));
    }

    public boolean isChecked() {
        return checked;
    }

    public void setChecked(boolean c) {
        if (c == checked) return;
        checked = c;
        if (listener != null) listener.onChanged(c);
        animateTo(c ? 1f : 0f);
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener l) {
        this.listener = l;
    }

    private void animateTo(float target) {
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(anim, target);
        va.setDuration(200);
        va.setInterpolator(new android.view.animation.DecelerateInterpolator());
        va.addUpdateListener(a -> {
            anim = (float) a.getAnimatedValue();
            invalidate();
        });
        va.start();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension((int) dp(56), (int) dp(34));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float radius = h / 2f;
        rect.set(0, 0, w, h);
        int trackColor = Color.argb((int) (60 + 120 * anim), 0x16, 0x9A, 0xFF);
        track.setColor(trackColor);
        canvas.drawRoundRect(rect, radius, radius, track);
        float tx = (w - h) * anim;
        canvas.drawCircle(dp(4) + h / 2f + tx, h / 2f, h / 2f - dp(4), thumb);
    }
}
