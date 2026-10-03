package com.zhiyu.imgx.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 玻璃按钮(重写)。
 *  - STYLE_PRIMARY: 强调色渐变胶囊(带高光, 有液体质感)
 *  - STYLE_GLASS  : 玻璃胶囊(折射背景 + 顶部镜面 + 描边)
 *  - STYLE_DANGER : 警示色
 * 按下有明显"挤压"回弹。
 */
public class GlassButton extends FrameLayout {

    public static final int STYLE_PRIMARY = 0;
    public static final int STYLE_GLASS = 1;
    public static final int STYLE_DANGER = 2;

    private static final int ACCENT = 0xFF0079FF;

    private final TextView tv;
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clipPath = new Path();
    private final RectF rect = new RectF();
    private int style = STYLE_PRIMARY;
    private float pressed = 0f;
    private GlassScene scene;

    public GlassButton(Context context, String text, int style) {
        super(context);
        setWillNotDraw(false);
        setLayerType(LAYER_TYPE_SOFTWARE, null);

        tv = new TextView(context);
        tv.setText(text);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(16);
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tv.setIncludeFontPadding(false);
        tv.setPadding(0, (int) dp(14), 0, (int) dp(14));
        addView(tv, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dp(1.1f));
        shadowPaint.setColor(0x26000000);
        shadowPaint.setShadowLayer(dp(4.5f), 0, dp(1.5f), 0x33000000);

        applyStyle(style);

        setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    animatePress(1f);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    animatePress(0f);
                    break;
            }
            return false;
        });
    }

    /** 绑定场景以启用玻璃折射(STYLE_GLASS 使用) */
    public void attachScene(GlassScene s) { this.scene = s; }

    private void animatePress(float to) {
        animate().scaleX(to == 1f ? 0.965f : 1f)
                .scaleY(to == 1f ? 0.94f : 1f)
                .setDuration(to == 1f ? 90 : 260)
                .setInterpolator(to == 1f ? new android.view.animation.DecelerateInterpolator()
                        : new android.view.animation.OvershootInterpolator(1.7f))
                .start();
        pressed = to;
        invalidate();
    }

    public void setText(String t) { tv.setText(t); }
    public String getText() { return tv.getText().toString(); }

    public void applyStyle(int style) {
        this.style = style;
        if (style == STYLE_PRIMARY) {
            tv.setTextColor(Color.WHITE);
            tv.setShadowLayer(dp(5), 0, dp(1.5f), 0x4D000000);
        } else if (style == STYLE_DANGER) {
            tv.setTextColor(Color.WHITE);
            tv.setShadowLayer(dp(5), 0, dp(1.5f), 0x4D000000);
        } else {
            tv.setTextColor(0xFF1B2430);
            tv.setShadowLayer(0, 0, 0, 0);
        }
        // 仍然给一点原生水波纹反馈
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(dp(28));
        mask.setColor(Color.WHITE);
        GradientDrawable content = new GradientDrawable();
        content.setCornerRadius(dp(28));
        content.setColor(0x00000000);
        setBackground(new RippleDrawable(ColorStateList.valueOf(0x22000000), content, mask));
        invalidate();
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        rect.set(dp(2), dp(2), w - dp(2), h - dp(4));
        clipPath.reset();
        clipPath.addRoundRect(rect, rect.height() / 2f, rect.height() / 2f, Path.Direction.CW);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (rect.isEmpty()) return;
        float r = rect.height() / 2f;

        if (style == STYLE_GLASS) {
            // 玻璃胶囊
            GlassRenderer.draw(canvas, scene, this, rect, r, 0x7AFFFFFF, 1.0f, shadowPaint);
            return;
        }

        // 实色渐变胶囊
        canvas.save();
        canvas.translate(0, dp(2.5f));
        Path sp = new Path();
        sp.addRoundRect(rect, r, r, Path.Direction.CW);
        canvas.drawPath(sp, shadowPaint);
        canvas.restore();

        int c1, c2, c3;
        if (style == STYLE_DANGER) {
            c1 = 0xFFFF7A6E; c2 = 0xFFF2453A; c3 = 0xFFC9332A;
        } else {
            c1 = 0xFF3FA0FF; c2 = ACCENT; c3 = 0xFF0057C2;
        }
        fillPaint.setShader(new android.graphics.LinearGradient(0, rect.top, 0, rect.bottom,
                new int[]{c1, c2, c3}, new float[]{0f, 0.52f, 1f},
                android.graphics.Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, r, r, fillPaint);

        // 顶部光泽(液体感)
        canvas.save();
        canvas.clipPath(clipPath);
        glossPaint.setShader(new android.graphics.LinearGradient(0, rect.top, 0, rect.bottom,
                new int[]{0x59FFFFFF, 0x14FFFFFF, 0x00000000},
                new float[]{0f, 0.45f, 1f}, android.graphics.Shader.TileMode.CLAMP));
        canvas.drawRect(rect, glossPaint);
        // 上缘细亮线
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeWidth(dp(1.3f));
        line.setColor(0x8CFFFFFF);
        canvas.drawLine(rect.left + r * 0.7f, rect.top + dp(2.4f),
                rect.right - r * 0.7f, rect.top + dp(2.4f), line);
        canvas.restore();

        // 描边
        strokePaint.setColor(0x2EFFFFFF);
        canvas.drawRoundRect(rect, r, r, strokePaint);
    }
}
