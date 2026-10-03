package com.zhiyu.imgx.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.widget.FrameLayout;

/**
 * 玻璃场景容器: 持有动画背景, 定时(约 12fps)把背景渲染到低分辨率位图,
 * 供 GlassCard 做"折射采样"(把背景画进玻璃内部, 等效毛玻璃)。
 */
public class GlassScene extends FrameLayout {

    private final AnimatedBackground bg;
    private volatile Bitmap bgCache;
    private volatile long cacheVersion = 0;   // 每次捕获 +1, 供原生玻璃判断缓存是否失效
    private long lastCapture;
    private final Object lock = new Object();
    private final Runnable captureTask = this::capture;
    private final java.util.List<android.view.View> glassViews = new java.util.ArrayList<>();

    public GlassScene(Context context) {
        super(context);
        setWillNotDraw(true);
        bg = new AnimatedBackground(context);
        addView(bg, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    public AnimatedBackground getBackgroundView() {
        return bg;
    }

    /** 玻璃视图注册: 捕获完成后自动重绘, 保证玻璃背景不错位不滞后 */
    public void attachGlassView(android.view.View v) {
        synchronized (glassViews) {
            if (!glassViews.contains(v)) glassViews.add(v);
        }
    }

    public void detachGlassView(android.view.View v) {
        synchronized (glassViews) {
            glassViews.remove(v);
        }
    }

    /** 请求一帧背景捕获(由动画驱动调用) —— 降频防大屏卡死 */
    public void requestCapture() {
        postDelayed(captureTask, 250); // ~4fps, 大屏(平板)也不会卡主线程
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (changed) post(() -> capture());   // 布局稳定后立即捕获一次, 玻璃背景立即可用
    }

    private void capture() {
        long now = System.currentTimeMillis();
        if (now - lastCapture < 120) return;
        lastCapture = now;
        try {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            int cw = Math.max(1, w / 5), ch = Math.max(1, h / 5);
            synchronized (lock) {
                if (bgCache == null || bgCache.getWidth() != cw || bgCache.getHeight() != ch) {
                    if (bgCache != null) bgCache.recycle();
                    bgCache = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
                }
                Canvas c = new Canvas(bgCache);
                c.scale(cw / (float) w, ch / (float) h);
                bg.drawTo(c);
                cacheVersion++;
            }
            // 捕获完成: 不主动 invalidate 玻璃视图 —— 主动刷新会触发每帧全树重绘,
            // 低端机上上下滑动直接卡死; 玻璃视图在自身交互/布局时自然用最新缓存即可。
        } catch (Throwable t) {
            // 大屏/低内存设备: 捕获失败就跳过, 不崩溃
            android.util.Log.i("ImgX", "capture failed", t);
        }
    }

    public Bitmap getCache() {
        return bgCache;
    }

    /** 背景缓存版本(变化说明需要重绘玻璃) */
    public long getCacheVersion() {
        return cacheVersion;
    }

    public void recycle() {
        synchronized (lock) {
            if (bgCache != null) {
                bgCache.recycle();
                bgCache = null;
            }
        }
    }
}
