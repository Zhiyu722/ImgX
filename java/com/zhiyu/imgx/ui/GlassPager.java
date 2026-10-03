package com.zhiyu.imgx.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * 液态滑动分页器 —— 移植 Liquidglass.js 的弹簧物理:
 *  - 拖动时手指直接控制位置(带边缘阻尼)
 *  - 松手用 100ms 窗口最小二乘速度估算甩动方向
 *  - 用欠阻尼弹簧(K=300, ζ≈0.55)回弹到目标页, 带液态过冲回弹
 *  - 相邻页视差 + 缩放, 制造深度
 */
public class GlassPager extends ViewGroup {

    public interface OnPageChangedListener {
        void onPageChanged(int index, float position);
    }

    // iOS 手感: 点击干脆(≈0.25s), 滑动松手从容(≈0.38s), 都是临界阻尼(不回弹)
    private static final double K_SWIPE = 140.0;
    private static final double K_CLICK = 620.0;   // 点击切页: 更快响应
    private static final double SPRING_DAMPING = 0.99;
    /** 当前生效的刚度(按交互类型切换) */
    private double activeK = K_CLICK;
    private static final double PARALLAX = 0.10;        // 视差比例
    private static final float NEIGHBOR_SCALE = 0.94f; // 相邻页缩放

    private final List<View> pages = new ArrayList<>();
    private final VelocityTracker1D velocityTracker = new VelocityTracker1D();
    private final int touchSlop;
    private final ValueAnimator springAnimator;

    private double posX;          // 当前滚动位置 px
    private double posVel;        // 弹簧速度 px/s
    private double dragStartPosX; // 拖动起点(px)
    private double targetX;       // 弹簧目标
    private boolean springRunning;
    private long lastFrameNanos;
    private float lastX = -1f;

    private float downX, downY;
    private boolean dragging;
    private boolean touchLocked;   // 本次手势已锁定方向(垂直→滚动, 不再抢)
    private int currentPage;
    private int width;
    private OnPageChangedListener listener;

    public GlassPager(Context context) {
        super(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        springAnimator = ValueAnimator.ofFloat(0f, 1f);
        springAnimator.setDuration(1000);
        springAnimator.setRepeatCount(ValueAnimator.INFINITE);
        springAnimator.addUpdateListener(a -> springTick());
        setWillNotDraw(true);
    }

    public void addPage(View v) {
        pages.add(v);
        addView(v, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        requestLayout();
    }

    public void setOnPageChangedListener(OnPageChangedListener l) {
        this.listener = l;
    }

    public int getCurrentPage() {
        return currentPage;
    }

    /** 是否正在被手指拖动(用于顶栏胶囊 1:1 跟手) */
    public boolean isDragging() {
        return dragging;
    }

    /** 拖动起点 posX(px), 用于顶栏胶囊 1:1 像素跟手 */
    public double getDragStartPosX() {
        return dragStartPosX;
    }

    /** 外部控制(顶栏拖动联动): 直接设置页面位置 */
    public void setPositionFraction(float pos, boolean animated) {
        double target = pos * width;
        if (animated) activeK = K_CLICK;
        if (animated) {
            targetX = target;
            startSpring();
        } else {
            stopSpring();
            posX = target;
            posVel = 0;
            applyScroll();
            notifyChanged();
        }
    }

    public void setCurrentPage(int index, boolean animate) {
        if (index < 0 || index >= pages.size()) return;
        activeK = K_CLICK;   // 点击切页: 快
        if (!animate) {
            posX = index * width;
            targetX = posX;
            posVel = 0;
            settleNow();
        } else {
            targetX = index * width;
            startSpring();
        }
    }

    private void startSpring() {
        springRunning = true;
        lastFrameNanos = System.nanoTime();
        springAnimator.start();
    }

    private void stopSpring() {
        springRunning = false;
        springAnimator.cancel();
    }

    private void springTick() {
        long nowNanos = System.nanoTime();
        double dt = Math.min(0.033, (nowNanos - lastFrameNanos) / 1e9);
        lastFrameNanos = nowNanos;
        double[] r = springStepUnderdamped(posX, posVel, targetX, dt,
                Math.sqrt(activeK), SPRING_DAMPING);
        posX = r[0];
        posVel = r[1];
        if (Math.abs(posX - targetX) < 0.4 && Math.abs(posVel) < 3.0) {
            posX = targetX;
            posVel = 0;
            springRunning = false;
            springAnimator.cancel();
        }
        applyScroll();
        notifyChanged();
    }

    private void settleNow() {
        applyScroll();
        notifyChanged();
    }

    /** 欠阻尼弹簧一步(移植自 Liquidglass.js springStepUnderdamped) */
    private static double[] springStepUnderdamped(double current, double velocity, double target,
                                                  double dt, double omegaN, double dampingRatio) {
        double x0 = current - target;
        double v0 = velocity;
        double omegaD = omegaN * Math.sqrt(Math.max(0, 1 - dampingRatio * dampingRatio));
        double decay = Math.exp(-dampingRatio * omegaN * dt);
        double cosWd = Math.cos(omegaD * dt);
        double sinWd = Math.sin(omegaD * dt);
        double offset = x0 * decay * cosWd + (v0 + dampingRatio * omegaN * x0) / omegaD * decay * sinWd;
        double b0 = (v0 + dampingRatio * omegaN * x0) / omegaD;
        double newVel = -dampingRatio * omegaN * offset + decay * (-x0 * omegaD * sinWd + b0 * omegaD * cosWd);
        return new double[]{target + offset, newVel};
    }

    private void applyScroll() {
        scrollTo((int) Math.round(posX), 0);
        float pw = Math.max(1, width);
        for (int i = 0; i < pages.size(); i++) {
            View v = pages.get(i);
            float offset = (float) (posX - i * pw);
            // 视差渐变: 当前页 1:1 跟手, 视差在半个页宽内从 0 渐增到最大(避免滞后感/跳变)
            float ramp = Math.min(1f, Math.abs(offset) / (pw * 0.5f));
            v.setTranslationX(offset * (float) PARALLAX * ramp);
            float scale = 1f - (1f - NEIGHBOR_SCALE) * Math.min(1f, Math.abs(offset) / pw);
            v.setScaleX(scale);
            v.setScaleY(scale);
        }
    }

    private void notifyChanged() {
        if (width <= 0) return;
        currentPage = (int) Math.round(posX / width);
        currentPage = Math.max(0, Math.min(currentPage, pages.size() - 1));
        if (listener != null) listener.onPageChanged(currentPage, (float) (posX / width));
    }

    /**
     * 逐页裁剪在自己的一格内: 防止相邻页内容在过渡时互相"抄"到对方区域。
     * (页面的视差/缩放仍然生效, 只是不允许越界绘制)
     */
    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (width <= 0) { super.dispatchDraw(canvas); return; }
        long t = getDrawingTime();
        for (int i = 0; i < pages.size(); i++) {
            View v = pages.get(i);
            if (v == null || v.getVisibility() != View.VISIBLE) continue;
            canvas.save();
            canvas.clipRect(i * width, 0, (i + 1) * width, getHeight());
            drawChild(canvas, v, t);
            canvas.restore();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        // 仅宽度变化(旋转)才重置位置; 高度变化(键盘弹出)保持当前页, 避免弹簧/胶囊错位
        if (w != ow && w > 0) {
            width = w;
            posX = currentPage * width;
        }
        scrollTo((int) posX, 0);
        notifyChanged();   // 关键: 尺寸变化后同步顶栏胶囊, 防止"两秒延迟"
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < pages.size(); i++) {
            pages.get(i).layout(i * getWidth(), 0, (i + 1) * getWidth(), getHeight());
        }
    }

    @Override
    protected void onMeasure(int w, int h) {
        for (View v : pages) {
            measureChild(v, MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(w), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(h), MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getSize(h));
    }

    // ================= 触摸 =================

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                dragging = false;
                touchLocked = false;
                return false;
            case MotionEvent.ACTION_MOVE: {
                float dx = ev.getX() - downX;
                float dy = ev.getY() - downY;
                if (!dragging && !touchLocked) {
                    // 灵敏判定: 水平分量 ≥ 竖向一半 → 翻页(容忍斜滑/抖动);
                    // 竖向 > 2倍水平 → 锁定为垂直滚动(不抢)
                    if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 0.5f) {
                        dragging = true;
                        dragStartPosX = posX;
                        stopSpring();
                        velocityTracker.reset();
                        velocityTracker.addPosition(System.currentTimeMillis(), posX);
                        return true;
                    } else if (Math.abs(dy) > touchSlop && Math.abs(dy) > Math.abs(dx) * 2f) {
                        touchLocked = true;
                    }
                }
                return dragging;
            }
            default:
                return dragging;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                stopSpring();
                velocityTracker.reset();
                velocityTracker.addPosition(System.currentTimeMillis(), posX);
                lastX = ev.getX();
                return true;
            case MotionEvent.ACTION_MOVE: {
                float x = ev.getX();
                if (lastX < 0) lastX = x;
                double delta = lastX - x;
                lastX = x;
                double next = posX + delta;
                double maxX = (pages.size() - 1) * width;
                if (next < 0) next *= 0.32;
                if (next > maxX) next = maxX + (next - maxX) * 0.32;
                posX = next;
                posVel = 0;
                velocityTracker.addPosition(System.currentTimeMillis(), posX);
                applyScroll();
                notifyChanged();
                return true;
            }
            case MotionEvent.ACTION_UP: {
                lastX = -1f;
                dragging = false;
                touchLocked = false;
                double v = velocityTracker.calculateVelocity(100);
                double posF = posX / Math.max(1, width);
                android.util.Log.d("ImgX", "posF=" + String.format("%.3f", posF)
                        + " v=" + (int) v + " x=" + (int) posX);
                // 就近页(拖过 50% 即翻页)
                int target = (int) Math.round(posF);
                // 速度方向: 手指向左滑(posX 增大)速度为正 → 下一页; 向右滑为负 → 上一页
                if (v > 380) {
                    target = (int) Math.floor(posF) + 1;   // 向左甩 → 下一页
                } else if (v < -380) {
                    target = (int) Math.ceil(posF) - 1;    // 向右甩 → 上一页
                }
                target = Math.max(0, Math.min(target, pages.size() - 1));
                android.util.Log.d("ImgX", "  → target=" + target);
                targetX = target * width;
                // 内容速度反向 + 大幅削弱并限幅: 甩动不再让页面"嗖"地飞过去
                double flingVel = -v * 0.22;      // iOS 式: 甩动只给一点初速度
                if (flingVel > 420) flingVel = 420;
                if (flingVel < -420) flingVel = -420;
                posVel = flingVel;
                activeK = K_SWIPE;   // 滑动松手: 慢而从容
                startSpring();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                lastX = -1f;
                dragging = false;
                touchLocked = false;
                stopSpring();
                targetX = Math.round(posX / Math.max(1, width)) * width;
                posVel = 0;
                startSpring();
                return true;
        }
        return true;
    }

    // ---------------- 最小二乘速度(移植 Liquidglass.js VelocityTracker1D) ----------------

    private static final class VelocityTracker1D {
        private static final int MAX_SAMPLES = 20;
        private final List<long[]> samples = new ArrayList<>(); // {t, p}

        void reset() {
            samples.clear();
        }

        void addPosition(long timeMillis, double position) {
            samples.add(new long[]{timeMillis, (long) position});
            if (samples.size() > MAX_SAMPLES) samples.remove(0);
        }

        double calculateVelocity(int windowMs) {
            if (samples.size() < 2) return 0;
            long now = samples.get(samples.size() - 1)[0];
            long cutoff = now - windowMs;
            int n = 0;
            double sumT = 0, sumP = 0, sumTT = 0, sumTP = 0;
            for (int i = samples.size() - 1; i >= 0; i--) {
                long[] s = samples.get(i);
                if (s[0] < cutoff) break;
                double tt = (s[0] - now) / 1e3;
                sumT += tt;
                sumP += s[1];
                sumTT += tt * tt;
                sumTP += tt * s[1];
                n++;
            }
            if (n < 2) return 0;
            double denom = n * sumTT - sumT * sumT;
            if (Math.abs(denom) < 1e-9) return 0;
            return (n * sumTP - sumT * sumP) / denom;
        }
    }
}
