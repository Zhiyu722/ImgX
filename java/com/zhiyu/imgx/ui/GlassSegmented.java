package com.zhiyu.imgx.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 玻璃分段顶栏(第三版: 交互统一 + 白色玻璃胶囊)。
 *
 * 交互约定:
 *   - 拖动顶栏: 每帧把"绝对页位置"交给回调 → 由分页器统一滚动;
 *     胶囊位置始终由 {@link #setPosition} 从分页器回流驱动, 拖动中 1:1, 不打架。
 *   - 点击标签/松手: 通过回调让分页器弹簧动画到目标页, 胶囊随之跟随。
 *
 * 视觉:
 *   - 面板: 玻璃(折射 + 顶部镜面 + 上亮下暗描边)
 *   - 胶囊: 白色玻璃 + 柔和投影 + 顶部光泽(选中深色字, 未选中中灰字)
 *   - 宽度自适应: 每段最多 {@link #MAX_SEG_DP}dp, 宽屏居中限宽
 */
public class GlassSegmented extends LinearLayout {

    public interface OnSelectedListener { void onSelected(int index); }
    public interface OnDragListener { void onDrag(float pos); }

    private static final float MAX_SEG_DP = 88f;      // 内容需要的段宽上限
    private static final float MAX_SEG_HARD_DP = 128f; // 手机放大后的单段硬上限
    private static final float MAX_SEG_LARGE_DP = 112f; // 平板/大屏单段硬上限(更短, 长标签仍够用)
    private static final float DRAG_SLOP_DP = 4f;

    private final String[] items;
    private int selected = 0;
    private float pillPos = 0f;      // 段单位(0..n-1)
    private float pillVel = 0f;
    private float targetPos = 0f;

    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint capFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint capGloss = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint capStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint capShadow = new Paint(Paint.ANTI_ALIAS_FLAG);

    private OnSelectedListener listener;
    private OnDragListener dragListener;
    private GlassScene scene;
    private ValueAnimator springAnim;
    private long lastStep;

    private float downX;
    private float dragStartPos;
    private boolean dragging;
    private boolean pressed;
    private boolean instantMode;   // 即时模式: 点选即停, 不跑玻璃弹簧动画(用于卡片内单选, 避免每帧重绘闪烁)

    public GlassSegmented(Context context, String[] items) {
        super(context);
        this.items = items != null ? items : new String[0];
        setOrientation(HORIZONTAL);
        setWillNotDraw(false);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setClipToPadding(false);

        shadowPaint.setColor(0x1F000000);
        shadowPaint.setShadowLayer(dp(5), 0, dp(1.5f), 0x28000000);   // 阴影减小

        capFill.setColor(0xF2FFFFFF);
        capStroke.setStyle(Paint.Style.STROKE);
        capStroke.setStrokeWidth(dp(0.9f));
        capStroke.setColor(0x2E5A6675);   // 淡灰描边, 白胶囊在白面板上也有边界
        pressPaint.setColor(0x14000000);
        capShadow.setColor(0x2E000000);
        capShadow.setShadowLayer(dp(3.5f), 0, dp(1f), 0x40000000);

        for (int i = 0; i < this.items.length; i++) {
            final int idx = i;
            TextView tv = new TextView(context);
            tv.setText(this.items[i]);
            tv.setTextSize(14.5f);
            tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            tv.setGravity(Gravity.CENTER);
            tv.setSingleLine(true);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);   // 段太窄时截断, 防止文字溢出到邻段重合
            LayoutParams lp = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
            tv.setLayoutParams(lp);
            tv.setOnClickListener(v -> { if (!dragging) selectNow(idx); });
            addView(tv);
        }
        updateTextColors();

        springAnim = ValueAnimator.ofFloat(0f, 1f);
        springAnim.setDuration(1000);
        springAnim.setRepeatCount(ValueAnimator.INFINITE);
        springAnim.addUpdateListener(a -> springStep());
    }

    // ==================== 对外 API ====================

    public void setOnSelectedListener(OnSelectedListener l) { this.listener = l; }
    public void setOnDragListener(OnDragListener l) { this.dragListener = l; }
    public void setInstantMode(boolean v) { this.instantMode = v; }
    public void attachScene(GlassScene s) {
        if (this.scene == s) return;
        if (this.scene != null) this.scene.detachGlassView(this);
        this.scene = s;
        if (s != null) s.attachGlassView(this);
        invalidate();
    }
    public int getSelectedIndex() { return selected; }
    public float getPageCount() { return items.length; }
    public float getCurrentPos() { return pillPos; }

    /** 由分页器回流驱动位置; instant=true 时 1:1 立即生效(拖动中) */
    public void setPosition(float pos, boolean instant) {
        if (items.length == 0) return;
        float clamped = Math.max(0f, Math.min(items.length - 1f, pos));
        if (Math.abs(clamped - pillPos) < 0.0005f && !springAnim.isRunning()) return;
        if (instant) {
            stopSpring();
            pillPos = clamped;
            pillVel = 0;
            targetPos = clamped;
            syncSelected();
            invalidate();
        } else {
            targetPos = clamped;
            if (Math.abs(clamped - pillPos) < 0.002f) {
                stopSpring();
                pillPos = clamped;
                pillVel = 0;
                syncSelected();
                invalidate();
                return;
            }
            if (!springAnim.isRunning()) { lastStep = System.nanoTime(); springAnim.start(); }
            springStep();   // 立即推进一帧, 避免"慢半拍"
        }
    }

    /** 点击标签(让分页器动画过去) */
    private void selectNow(int index) {
        if (index < 0 || index >= items.length) return;
        boolean changed = index != selected;
        selected = index;
        targetPos = index;
        if (instantMode) {   // 即时模式: 点选即停, 无玻璃弹簧动画(卡片内单选不闪)
            stopSpring();
            pillPos = index;
            pillVel = 0;
            updateTextColors();
            invalidate();
            if (changed && listener != null) listener.onSelected(index);
            return;
        }
        // 点击立即起跳(不等分页器回流), 观感零延迟
        stopSpring();
        lastStep = System.nanoTime();
        springAnim.start();
        updateTextColors();
        if (changed && listener != null) listener.onSelected(index);
        if (!changed) {
            stopSpring();
            pillPos = index;
            pillVel = 0;
            invalidate();
        }
    }

    // ==================== 布局: 面板限宽 + 文字对齐 ====================

    private float maxSeg() { return dp(MAX_SEG_DP); }

    /** 标签实际需要的段宽(px): 最长标签 + 左右内边距, 并限制在 [56dp, 118dp] */
    private float contentSegWidth() {
        float maxText = 0f;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v instanceof TextView) {
                TextView tv = (TextView) v;
                maxText = Math.max(maxText, tv.getPaint().measureText(tv.getText().toString()));
            }
        }
        float need = maxText + dp(30);                     // 左右各 15dp
        float lo = dp(82);                                 // 下限: 顶栏舒展但不冗长
        float hi = dp(124);
        if (need < lo) need = lo;
        if (need > hi) need = hi;
        return need;
    }

    /**
     * 面板宽度:
     *  1. 至少满足内容所需(长标签不被裁)
     *  2. 手机(<600dp)按屏宽比例略微铺开; 平板/大屏以内容为准, 不被屏宽拉长
     *  3. 单段不超过硬上限, 且整体不超屏宽
     */
    public float getPanelWidth() {
        int n = Math.max(1, items.length);
        float need = contentSegWidth() * n;
        float avail = getWidth();
        if (avail <= 0) return need;

        float density = getResources().getDisplayMetrics().density;
        boolean large = avail / density >= 600f;           // 平板 / 大屏判定
        float frac = large ? 0.30f : 0.52f;                 // 大屏只留 30%, 不再拖长
        float segCap = dp(large ? MAX_SEG_LARGE_DP : MAX_SEG_HARD_DP);

        float pw = Math.max(need, avail * frac);
        if (pw / n > segCap) pw = segCap * n;
        if (pw > avail) pw = avail;
        return pw;
    }

    /** 面板变窄时按比例缩小标签字号, 保证不裁字 */
    private void fitTextSize() {
        float seg = getPanelWidth() / Math.max(1, items.length);
        float avail = seg - dp(10);
        if (avail <= 0) return;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (!(v instanceof TextView)) continue;
            TextView tv = (TextView) v;
            float base = 14.5f;
            tv.setTextSize(base);
            tv.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            float w = tv.getPaint().measureText(tv.getText().toString());
            if (w > avail) {
                float scaled = base * (avail / w) * 0.96f;
                if (scaled < 8.5f) scaled = 8.5f;
                tv.setTextSize(scaled);
            }
        }
    }

    public float getPanelLeft() {
        return Math.max(0f, (getWidth() - getPanelWidth()) / 2f);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // 延迟到布局稳定后一次性设置面板边距/字号: 启动布局阶段宽度渐进变化,
        // 若每次 onSizeChanged 立即 setPadding 会让文字/胶囊左右跳动错位。
        post(() -> {
            int side = (int) getPanelLeft();
            if (getPaddingLeft() != side || getPaddingRight() != side) {
                setPadding(side, getPaddingTop(), side, getPaddingBottom());
            }
            fitTextSize();
        });
    }

    @Override
    protected void onDetachedFromWindow() {
        if (scene != null) scene.detachGlassView(this);
        super.onDetachedFromWindow();
    }

    public float getSegmentWidth() {
        return getPanelWidth() / Math.max(1, items.length);
    }

    // ==================== 触摸: 拖动整条顶栏切页 ====================

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                dragStartPos = pillPos;
                dragging = false;
                pressed = true;
                invalidate();
                return false;          // 先让子视图拿到点击
            case MotionEvent.ACTION_MOVE:
                if (!dragging && Math.abs(ev.getX() - downX) > dp(DRAG_SLOP_DP)) {
                    dragging = true;
                    pressed = false;
                    stopSpring();
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                return dragging;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                pressed = false;
                invalidate();
                return dragging;
            default:
                return dragging;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                dragStartPos = pillPos;
                dragging = true;
                stopSpring();
                return true;
            case MotionEvent.ACTION_MOVE: {
                float seg = getSegmentWidth();
                if (seg <= 0) return true;
                float np = dragStartPos + (ev.getX() - downX) / seg;
                np = Math.max(0f, Math.min(items.length - 1f, np));
                pillPos = np;          // 1:1 立即跟随(零延迟)
                pillVel = 0;
                targetPos = np;
                syncSelected();
                invalidate();
                if (dragListener != null) dragListener.onDrag(np);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                dragging = false;
                int nearest = Math.max(0, Math.min(items.length - 1, Math.round(pillPos)));
                boolean changed = nearest != selected;
                selected = nearest;
                targetPos = nearest;
                updateTextColors();
                if (changed && listener != null) listener.onSelected(nearest);
                if (dragListener != null) dragListener.onDrag(nearest);
                if (!springAnim.isRunning()) { lastStep = System.nanoTime(); springAnim.start(); }
                return true;
            }
        }
        return super.onTouchEvent(ev);
    }

    private void syncSelected() {
        int nearest = Math.max(0, Math.min(items.length - 1, Math.round(pillPos)));
        if (nearest != selected) {
            selected = nearest;
            updateTextColors();
        }
    }

    private void stopSpring() {
        if (springAnim.isRunning()) springAnim.cancel();
    }

    private void updateTextColors() {
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v instanceof TextView) {
                TextView tv = (TextView) v;
                // iOS 风格: 颜色按"与胶囊的距离"连续过渡(胶囊滑过时标签平滑变深)
                float d = Math.abs(i - pillPos);
                float t = Math.max(0f, Math.min(1f, 1f - d));
                tv.setTextColor(blend(0xFF6E7A88, 0xFF0D1116, t));
                tv.setTextSize(14.5f + 0.5f * t);
                tv.setShadowLayer(0, 0, 0, 0);
            }
        }
    }

    /** 颜色线性插值(用于标签过渡) */
    private static int blend(int from, int to, float t) {
        int a = (int) ((from >>> 24) * (1 - t) + (to >>> 24) * t);
        int r = (int) (((from >> 16) & 0xFF) * (1 - t) + ((to >> 16) & 0xFF) * t);
        int g = (int) (((from >> 8) & 0xFF) * (1 - t) + ((to >> 8) & 0xFF) * t);
        int b = (int) ((from & 0xFF) * (1 - t) + (to & 0xFF) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // ==================== 弹簧(欠阻尼) ====================

    private void springStep() {
        long now = System.nanoTime();
        float dt = Math.min(0.032f, (now - lastStep) / 1e9f);
        lastStep = now;
        if (dt <= 0) return;
        float x = pillPos - targetPos;
        float omega = 32f;    // 点击/回位更快, 拖沓感消除
        float zeta = 0.99f;   // 近临界阻尼: 到位即停, 不回弹过头
        float accel = -omega * omega * x - 2f * zeta * omega * pillVel;
        pillVel += accel * dt;
        pillPos += pillVel * dt;
        updateTextColors();
        invalidate();
        if (Math.abs(pillPos - targetPos) < 0.0012f && Math.abs(pillVel) < 0.03f) {
            pillPos = targetPos;
            pillVel = 0;
            stopSpring();
            invalidate();
        }
    }

    // ==================== 绘制 ====================

    @Override
    protected void onDraw(Canvas canvas) {
        if (items.length == 0) return;
        float h = getHeight();
        float pw = getPanelWidth();
        float pl = getPanelLeft();
        RectF panel = new RectF(pl + dp(1), dp(1.5f), pl + pw - dp(1), h - dp(1.5f));
        float radius = panel.height() / 2f;

        // 面板: 玻璃
        // 浅灰玻璃托盘(罩白少 + 高光弱)
        GlassRenderer.draw(canvas, scene, this, panel, radius, 0x30FFFFFF, 1.0f, 0.35f, shadowPaint);
        // 再加一层极淡的灰纱: 保证面板是"浅灰托盘"而不是近白, 白色胶囊才能清晰浮起
        Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
        veil.setColor(0x1C5A6675);
        canvas.drawRoundRect(panel, radius, radius, veil);

        // 胶囊(白色玻璃): 在两段中心之间连续滑动
        float seg = pw / Math.max(1, items.length);
        float capsuleW = Math.max(dp(56), seg - dp(9));
        float centerX = pl + (pillPos + 0.5f) * seg;
        float inset = dp(3.5f);
        RectF cap = new RectF(centerX - capsuleW / 2f, panel.top + inset,
                centerX + capsuleW / 2f, panel.bottom - inset);
        float capR = cap.height() / 2f;

        // 投影
        canvas.save();
        canvas.translate(0, dp(1.6f));
        canvas.drawRoundRect(cap, capR, capR, capShadow);
        canvas.restore();

        // 主体
        canvas.drawRoundRect(cap, capR, capR, capFill);

        // 体积感: 上亮下暗
        capGloss.setShader(new LinearGradient(0, cap.top, 0, cap.bottom,
                new int[]{0x66FFFFFF, 0x1AFFFFFF, 0x00000000, 0x0F000000},
                new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(cap, capR, capR, capGloss);

        // 上缘细亮线
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeWidth(dp(1f));
        line.setColor(0xB3FFFFFF);
        canvas.drawLine(cap.left + capR * 0.55f, cap.top + dp(1.2f),
                cap.right - capR * 0.55f, cap.top + dp(1.2f), line);

        canvas.drawRoundRect(cap, capR, capR, capStroke);

        // 按压反馈
        if (pressed) {
            Path pp = new Path();
            pp.addRoundRect(panel, radius, radius, Path.Direction.CW);
            canvas.drawPath(pp, pressPaint);
        }
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
}
