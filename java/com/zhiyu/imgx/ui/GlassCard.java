package com.zhiyu.imgx.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.widget.FrameLayout;

/**
 * 玻璃卡片(重写) —— 使用统一的 {@link GlassRenderer} 绘制:
 * 折射 + 磨砂 + 增艳 + 罩白 + 顶部镜面 + 斜向光泽 + 边缘内阴影 + 上亮下暗描边。
 *
 * 支持两种外观:
 *  - 默认: 玻璃(半透明白), 能看到背景的色彩流动
 *  - {@link #setSolid(boolean)} 纯白不透明: 适合文字密集的内容区
 */
public class GlassCard extends FrameLayout {

    private final RectF rect = new RectF();
    private final Path clipPath = new Path();
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint solidPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint solidStroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float radius = dp(26);
    private float refraction = 1.0f;
    private int tintAlpha = 0x8A;      // 罩白透明度
    private boolean solid = false;      // 纯白不透明模式
    private GlassScene scene;

    public GlassCard(Context context) {
        super(context);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setWillNotDraw(false);
        shadowPaint.setColor(0x1A000000);
        shadowPaint.setShadowLayer(dp(5), 0, dp(2f), 0x24000000);   // 阴影减小
        solidPaint.setColor(0xFFFBFCFD);
        solidStroke.setStyle(Paint.Style.STROKE);
        solidStroke.setStrokeWidth(dp(1));
        solidStroke.setColor(0x1AE0E6EC);
    }

    public void attachScene(GlassScene s) { this.scene = s; }

    public GlassCard setRadius(float radiusDp) {
        this.radius = dp(radiusDp);
        invalidate();
        return this;
    }

    /** @param scale 1.0 = 默认折射; >1 更强 */
    public GlassCard setRefraction(float scale) {
        this.refraction = scale;
        invalidate();
        return this;
    }

    /** 纯白不透明卡片(文字密集区更清晰) */
    public GlassCard setSolid(boolean s) {
        this.solid = s;
        invalidate();
        return this;
    }

    /** 罩白透明度 0-255 */
    public GlassCard setTintAlpha(int a) {
        this.tintAlpha = Math.max(0, Math.min(255, a));
        invalidate();
        return this;
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        rect.set(dp(3), dp(2), w - dp(3), h - dp(5));
        clipPath.reset();
        clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (rect.isEmpty()) return;

        if (solid) {
            // 纯白卡片: 阴影 + 白底 + 顶部微光 + 淡边
            canvas.save();
            canvas.translate(0, dp(3));
            Path sp = new Path();
            sp.addRoundRect(rect, radius, radius, Path.Direction.CW);
            Paint sh = new Paint(Paint.ANTI_ALIAS_FLAG);
            sh.setColor(0x14000000);
            canvas.drawPath(sp, sh);
            canvas.restore();
            canvas.drawPath(clipPath, solidPaint);
            Paint gloss = new Paint(Paint.ANTI_ALIAS_FLAG);
            gloss.setShader(new android.graphics.LinearGradient(0, rect.top, 0, rect.top + rect.height() * 0.35f,
                    0x18FFFFFF, 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP));
            canvas.save();
            canvas.clipPath(clipPath);
            canvas.drawRect(rect, gloss);
            canvas.restore();
            canvas.drawPath(clipPath, solidStroke);
        } else {
            // 卡片面积大且内容静态: live=false, 只在几何变化时重算(防掉帧)
            GlassRenderer.draw(canvas, scene, this, rect, radius, tintAlpha,
                    refraction, 1.0f, 0f, false, shadowPaint);
        }
    }
}
