package com.zhiyu.imgx.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * 原生(C 库)液态玻璃渲染桥。
 *
 * 由 {@link GlassRenderer} 调用: 能用原生就用原生, 否则回退到 Java Canvas 实现。
 *
 * 工作方式:
 *   1. 从 GlassScene 的背景缓存里取出面板区域(放大到面板像素尺寸 → 天然磨砂)
 *   2. 交给 C 库做折射/罩色/亮边/镜面渲染
 *   3. 结果缓存为 Bitmap 直接绘制; 背景没变时跳过重算
 */
public final class NativeGlass {

    private static boolean tried = false;
    private static boolean ok = false;

    /** 单个面板的渲染器状态(每个面板一个实例, 复用缓冲) */
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private int[] backdrop, dst, cachePixels;
    private Bitmap bmp;
    private int pw, ph;
    /** 阴影留白(px): 位图比面板大一圈, 让投影完整画出来 */
    private float pad = 0f;
    private Bitmap lastCache;
    private long lastVersion = -1;
    private float lastL, lastT, lastR, lastB, lastRadius;

    private static final float[] GEOM = new float[6];
    private static final float[] EFF = new float[6];
    private static final int[] COLORS = new int[3];

    private NativeGlass() {}

    /** 原生库是否可用(失败一次就不再重试, 直接走 Java 回退) */
    public static synchronized boolean available() {
        if (!tried) {
            tried = true;
            try {
                System.loadLibrary("liquidglass_jni");
                String v = LiquidGlass.nativeVersion();
                android.util.Log.i("ImgX", "native liquid glass: " + v);
                ok = v != null;
            } catch (Throwable t) {
                android.util.Log.i("ImgX", "native liquid glass unavailable: " + t);
                ok = false;
            }
        }
        return ok;
    }

    /** 每个 View 绑定一个渲染器实例 */
    public static NativeGlass of(View v) {
        Object o = v.getTag(0x7f0f0001);
        if (o instanceof NativeGlass) return (NativeGlass) o;
        NativeGlass g = new NativeGlass();
        v.setTag(0x7f0f0001, g);
        return g;
    }

    /**
     * 用原生库绘制一块玻璃面板。
     *
     * @param tintAlpha 罩色透明度(0-255)
     * @param specular  镜面/上缘高光强度(0-1)
     * @param bodyAlpha 实体感(0-1), 顶栏等需要遮挡滚动内容时用
     * @return true = 已绘制; false = 条件不满足(调用方回退 Java 实现)
     */
    public boolean drawPanel(Canvas canvas, GlassScene scene, View self,
                             RectF rect, float radiusPx, int tintAlpha,
                             float specular, float bodyAlpha, boolean dark) {
        return drawPanel(canvas, scene, self, rect, radiusPx, tintAlpha,
                specular, bodyAlpha, dark, true);
    }

    /**
     * @param live true = 跟随背景动画重算(顶栏/按钮); false = 只在几何变化时重算(卡片等大面积静态面板,
     *             避免每帧重算造成掉帧)
     */
    public boolean drawPanel(Canvas canvas, GlassScene scene, View self,
                             RectF rect, float radiusPx, int tintAlpha,
                             float specular, float bodyAlpha, boolean dark, boolean live) {
        if (scene == null) return false;
        Bitmap cache = scene.getCache();
        if (cache == null || cache.isRecycled()) return false;

        final float density = self.getResources().getDisplayMetrics().density;
        // 留白必须 >= 阴影半径 + 偏移 + 余量, 否则阴影被裁成硬边
        pad = Math.max(12f, 34f * density);
        final int w = (int) Math.ceil(rect.width());
        final int h = (int) Math.ceil(rect.height());
        if (w <= 2 || h <= 2 || w + 2 * pad > 2600 || h + 2 * pad > 2600) return false;

        ensure(w, h);

        // 背景未变且几何未变 → 直接用缓存位图
        long ver = live ? scene.getCacheVersion() : 0L;   // live=false → 忽略背景版本变化
        boolean same = (ver == lastVersion)
                && rect.left == lastL && rect.top == lastT
                && rect.right == lastR && rect.bottom == lastB
                && radiusPx == lastRadius
                && bmp != null;
        if (!same) {
            if (!render(scene, self, cache, rect, w, h, radiusPx, tintAlpha,
                    specular, bodyAlpha, dark)) {
                return false;
            }
            lastVersion = ver;
            lastL = rect.left; lastT = rect.top;
            lastR = rect.right; lastB = rect.bottom;
            lastRadius = radiusPx;
        }
        canvas.drawBitmap(bmp, Math.round(rect.left - pad), Math.round(rect.top - pad), paint);
        return true;
    }

    private void ensure(int w, int h) {
        int bw = w + (int) (2 * pad), bh = h + (int) (2 * pad);
        if (pw == bw && ph == bh && bmp != null) return;
        pw = bw; ph = bh;
        backdrop = new int[bw * bh];
        dst = new int[bw * bh];
        if (bmp != null) bmp.recycle();
        bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
    }

    private boolean render(GlassScene scene, View self, Bitmap cache, RectF rect,
                           int w, int h, float radiusPx, int tintAlpha,
                           float specular, float bodyAlpha, boolean dark) {
        final int cw = cache.getWidth(), ch = cache.getHeight();
        if (cachePixels == null || cachePixels.length != cw * ch || lastCache != cache) {
            cachePixels = new int[cw * ch];
            try {
                cache.getPixels(cachePixels, 0, cw, 0, 0, cw, ch);
            } catch (Throwable t) {
                return false;
            }
            lastCache = cache;
        }

        // 面板在场景中的位置
        int[] loc = new int[2];
        self.getLocationInWindow(loc);
        int[] sloc = new int[2];
        scene.getLocationInWindow(sloc);
        float ox = loc[0] - sloc[0] + rect.left - pad;
        float oy = loc[1] - sloc[1] + rect.top - pad;
        final float sx = cw / (float) Math.max(1, scene.getWidth());
        final float sy = ch / (float) Math.max(1, scene.getHeight());

        // 放大采样 → 天然磨砂(必须覆盖整张位图, 含阴影留白)
        for (int y = 0; y < ph; y++) {
            int cyy = (int) ((oy + y) * sy);
            if (cyy < 0) cyy = 0;
            if (cyy >= ch) cyy = ch - 1;
            int rowIn = cyy * cw;
            int rowOut = y * pw;
            for (int x = 0; x < pw; x++) {
                int cxx = (int) ((ox + x) * sx);
                if (cxx < 0) cxx = 0;
                if (cxx >= cw) cxx = cw - 1;
                backdrop[rowOut + x] = cachePixels[rowIn + cxx];
            }
        }
        // 轻微平滑, 抹掉放大的方块感
        LiquidGlass.blur(backdrop, pw, ph, 3);
        // 目标缓冲保持"全透明"起底: 只让玻璃本体和阴影落在上面。
        // (若在这里垫一层采样背景, 每块玻璃都会重画一遍背景, 相邻面板之间会出现可见拼块)
        java.util.Arrays.fill(dst, 0);

        // 参数(像素单位)
        float density = self.getResources().getDisplayMetrics().density;

        GEOM[0] = Math.max(2f, radiusPx);
        GEOM[1] = 0f;                        // 已在上游放大磨砂, 不再二次模糊
        GEOM[2] = 1.035f;                    // 折射(透镜感)
        GEOM[3] = 0.22f;
        GEOM[4] = 0.60f;
        GEOM[5] = 1f * density;              // 描边
        EFF[0] = Math.max(0f, Math.min(1f, specular * 0.85f));   // 上缘亮边
        EFF[1] = Math.max(0f, Math.min(1f, specular * 0.45f));   // 左上镜面
        EFF[2] = dark ? 0.10f : 0.035f;   // 很淡的阴影                           // 投影(改柔: 更淡更大)
        EFF[3] = 3.5f * density;
        EFF[4] = 22f * density;
        EFF[5] = Math.max(0f, Math.min(0.85f, bodyAlpha));       // 实体感
        COLORS[0] = (tintAlpha & 0xFF) << 24 | 0x00FFFFFF;
        COLORS[1] = ((int) (tintAlpha * 0.45f) & 0xFF) << 24 | 0x00FFFFFF;
        COLORS[2] = dark ? 0x38FFFFFF : 0xB8FFFFFF;

        try {
            LiquidGlass.drawGlass(dst, pw, ph, backdrop, pw, ph,
                    pad, pad, (float) w, (float) h, GEOM, EFF, COLORS, dark);
        } catch (Throwable t) {
            android.util.Log.i("ImgX", "native glass render failed", t);
            return false;
        }
        bmp.setPixels(dst, 0, pw, 0, 0, pw, ph);
        return true;
    }
}
