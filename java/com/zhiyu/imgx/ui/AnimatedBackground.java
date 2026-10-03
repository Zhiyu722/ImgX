package com.zhiyu.imgx.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

import java.util.Random;

/**
 * 玻璃场景背景(重写):
 *   浅色渐变 + 缓慢漂移的柔光色块。
 *   色块让玻璃面板的折射/磨砂真正"看得见"(纯色渐变会让玻璃看起来像没效果)。
 */
public class AnimatedBackground extends View {

    // 底色: 浅灰蓝渐变
    private static final int[] BG_COLORS = {0xFFD3D9E0, 0xFFE3E9EF, 0xFFEFF4F9};
    // 柔光色块: 淡蓝 / 淡紫 / 淡青 / 暖白(低饱和)
    private static final int[][] BLOBS = {
            {0x5A8FB8FF, 0x008FB8FF},
            {0x4AA88CFF, 0x00A88CFF},
            {0x469EDCFF, 0x009EDCFF},
            {0x40BFD4FF, 0x00BFD4FF},
            {0x38FFFFFF, 0x00FFFFFF},
    };

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint[] blobPaints = new Paint[BLOBS.length];
    private final float[] bx = new float[BLOBS.length];
    private final float[] by = new float[BLOBS.length];
    private final float[] br = new float[BLOBS.length];
    private final float[] vx = new float[BLOBS.length];
    private final float[] vy = new float[BLOBS.length];
    private final Random rnd = new Random(7);
    private long lastT = 0;
    private Runnable frameCallback;

    public AnimatedBackground(Context context) {
        super(context);
        for (int i = 0; i < BLOBS.length; i++) {
            blobPaints[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
            bx[i] = rnd.nextFloat();
            by[i] = rnd.nextFloat();
            br[i] = 0.32f + rnd.nextFloat() * 0.34f;      // 相对屏幕短边
            vx[i] = (rnd.nextFloat() - 0.5f) * 0.016f;
            vy[i] = (rnd.nextFloat() - 0.5f) * 0.012f;
        }
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        bgPaint.setShader(new LinearGradient(0, 0, w * 0.35f, h, BG_COLORS, null,
                Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = System.currentTimeMillis();
        if (lastT == 0) lastT = now;
        float dt = Math.min(64f, now - lastT) / 1000f;
        lastT = now;
        tick(dt);
        drawTo(canvas);
        if (frameCallback != null) frameCallback.run();
        postInvalidateDelayed(33);   // ~30fps
    }

    public void setFrameCallback(Runnable r) { this.frameCallback = r; }

    /** 推进时间(不绘制) */
    public void tick(float dt) {
        for (int i = 0; i < BLOBS.length; i++) {
            bx[i] += vx[i] * dt;
            by[i] += vy[i] * dt;
            if (bx[i] < -0.5f) bx[i] = 1.4f;
            if (bx[i] > 1.5f) bx[i] = -0.4f;
            if (by[i] < -0.5f) by[i] = 1.4f;
            if (by[i] > 1.5f) by[i] = -0.4f;
        }
    }

    /** 绘制当前状态到任意画布(供 GlassScene 捕获) */
    public void drawTo(Canvas canvas) {
        int w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
        canvas.drawRect(0, 0, w, h, bgPaint);
        float base = Math.min(w, h);
        for (int i = 0; i < BLOBS.length; i++) {
            float r = br[i] * base;
            blobPaints[i].setShader(new RadialGradient(0, 0, r, BLOBS[i][0], BLOBS[i][1],
                    Shader.TileMode.CLAMP));
            canvas.save();
            canvas.translate(bx[i] * w, by[i] * h);
            canvas.drawCircle(0, 0, r, blobPaints[i]);
            canvas.restore();
        }
    }
}
