package com.zhiyu.imgx.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * 液态玻璃渲染器(全新重写)。
 *
 * 渲染配方(自下而上):
 *   0. 投影      —— 柔和下坠阴影
 *   1. 折射      —— 采样背景位图, 中心轻微放大 + 贴边环带额外弯折(越靠边越强)
 *   2. 磨砂      —— 低分辨率背景被放大绘制, 天然柔化(毛玻璃)
 *   3. 增艳      —— 轻微提饱和/提亮, 玻璃下的颜色更通透
 *   4. 罩白      —— 半透明白, 保证内容可读
 *   5. 顶部镜面  —— 上缘明亮弧带 + 一道细亮线(最强高光)
 *   6. 斜向光泽  —— 左上到右下的柔光扫过
 *   7. 边缘内阴影—— 下缘偏重, 形成玻璃厚度
 *   8. 描边      —— 上亮下暗的渐变描边(rim light)
 */
public final class GlassRenderer {

    private GlassRenderer() {}

    // 复用的绘制画笔(主线程串行绘制, 安全): 避免每帧 onDraw 重复分配对象导致卡顿
    private static final Paint P_REFRACT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint P_EDGE = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint P_TINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint P_BASE = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint P_SPEC = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint P_LINE = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint P_SHEEN = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint P_INNER = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint P_RIM = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** 兼容旧调用: 默认罩白 + 默认折射 */
    public static void drawGlass(Canvas canvas, GlassScene scene, View self,
                                 RectF rect, float radius, Paint shadowPaint) {
        draw(canvas, scene, self, rect, radius, 0x99FFFFFF, 1.0f, shadowPaint);
    }

    /**
     * 绘制玻璃面板。
     * @param tintAlpha 罩白透明度(0-255)
     * @param refraction 折射强度(1.0 = 默认, 0 = 不折射)
     */
    public static void draw(Canvas canvas, GlassScene scene, View self,
                            RectF rect, float radius, int tintAlpha,
                            float refraction, Paint shadowPaint) {
        draw(canvas, scene, self, rect, radius, tintAlpha, refraction, 1.0f, shadowPaint);
    }

    /**
     * 完整参数版。
     * @param specular 顶部镜面高光强度(1.0 默认; 顶栏等需要"浅灰托盘"感时可调低)
     */
    public static void draw(Canvas canvas, GlassScene scene, View self,
                            RectF rect, float radius, int tintAlpha,
                            float refraction, float specular, Paint shadowPaint) {
        draw(canvas, scene, self, rect, radius, tintAlpha, refraction, specular, 0f, shadowPaint);
    }

    /**
     * 完整参数版(含实体感)。
     * 若原生 C 玻璃库可用则用原生渲染, 否则回退到下面的 Java Canvas 实现。
     */
    public static void draw(Canvas canvas, GlassScene scene, View self,
                            RectF rect, float radius, int tintAlpha,
                            float refraction, float specular, float bodyAlpha,
                            Paint shadowPaint) {
        draw(canvas, scene, self, rect, radius, tintAlpha, refraction, specular,
                bodyAlpha, true, shadowPaint);
    }

    /** @param live false = 静态面板(不跟随背景动画重算, 省 CPU 防掉帧) */
    public static void draw(Canvas canvas, GlassScene scene, View self,
                            RectF rect, float radius, int tintAlpha,
                            float refraction, float specular, float bodyAlpha,
                            boolean live, Paint shadowPaint) {
        if (self != null && NativeGlass.available()) {
            // 本应用只有浅色 UI(无深色主题资源): 原生玻璃恒定浅色配色。
            // 否则系统切到深色模式时, 深色阴影/罩色会在浅色界面上变成"黑框"。
            if (NativeGlass.of(self).drawPanel(canvas, scene, self, rect, radius,
                    tintAlpha, specular, bodyAlpha, false, live)) {
                return;
            }
        }
        drawJava(canvas, scene, self, rect, radius, tintAlpha, refraction, specular, live, shadowPaint);
    }

    /** Java Canvas 实现(原生不可用时的回退, 也是参考实现) */
    private static void drawJava(Canvas canvas, GlassScene scene, View self,
                                 RectF rect, float radius, int tintAlpha,
                                 float refraction, float specular, boolean live, Paint shadowPaint) {
        final Path shape = new Path();
        shape.addRoundRect(rect, radius, radius, Path.Direction.CW);

        // ---------- 0. 投影 ----------
        if (shadowPaint != null) {
            canvas.save();
            canvas.translate(0, Math.max(2f, rect.height() * 0.06f));
            Path sp = new Path();
            sp.addRoundRect(rect, radius, radius, Path.Direction.CW);
            canvas.drawPath(sp, shadowPaint);
            canvas.restore();
        }

        final Bitmap cache = scene != null ? scene.getCache() : null;
        final float w = (self != null && self.getWidth() > 0) ? self.getWidth() : rect.width();
        final float h = (self != null && self.getHeight() > 0) ? self.getHeight() : rect.height();

        canvas.save();
        canvas.clipPath(shape);

        if (cache != null) {
            // ---------- 1/2/3. 折射 + 磨砂 + 增艳 ----------
            int[] loc = new int[2];
            if (self != null) self.getLocationInWindow(loc);
            int[] sceneLoc = new int[2];
            if (scene != null) scene.getLocationInWindow(sceneLoc);
            float dx = Math.max(0, loc[0] - sceneLoc[0]);
            float dy = Math.max(0, loc[1] - sceneLoc[1]);
            float scale = cache.getWidth() / (float) Math.max(1, scene.getWidth());
            float srcL = dx * scale, srcT = dy * scale;
            float srcR = Math.min(cache.getWidth(), srcL + w * scale);
            float srcB = Math.min(cache.getHeight(), srcT + h * scale);
            android.graphics.Rect src = new android.graphics.Rect(
                    (int) srcL, (int) srcT,
                    Math.max((int) srcL + 1, (int) srcR),
                    Math.max((int) srcT + 1, (int) srcB));

            float cx = rect.centerX(), cy = rect.centerY();
            float hw = rect.width() / 2f, hh = rect.height() / 2f;

            // 中心轻微放大: 玻璃"厚度"的基础折射
            float k = 1.0f + 0.055f * refraction;
            Paint p = P_REFRACT;
            p.setFilterBitmap(true);
            p.setColorFilter(vibrancyFilter());
            canvas.drawBitmap(cache, src,
                    new RectF(cx - hw * k, cy - hh * k, cx + hw * k, cy + hh * k), p);

            // 贴边环带额外弯折: 越靠边越明显
            // live=false(静态卡片/滚动过渡): 跳过环带, 每帧只做基础采样, 滑动不掉帧
            float band = Math.min(rect.width(), rect.height()) * 0.22f;
            if (band > 4f && refraction > 0f && live) {
                Paint edgeP = P_EDGE;
                edgeP.setFilterBitmap(true);
                float k2 = 1.0f + 0.16f * refraction;
                for (int i = 0; i < 3; i++) {
                    float f = (i + 1) / 3f;
                    edgeP.setAlpha((int) (30 * (1f - f * 0.55f)));
                    float kk = 1.0f + (k2 - 1.0f) * f;
                    Path ring = new Path();
                    ring.addRoundRect(new RectF(cx - hw * kk, cy - hh * kk,
                                    cx + hw * kk, cy + hh * kk),
                            radius * kk, radius * kk, Path.Direction.CW);
                    float inset = band * f;
                    Path inner = new Path();
                    inner.addRoundRect(new RectF(rect.left + inset * 0.9f, rect.top + inset * 0.5f,
                                    rect.right - inset * 0.9f, rect.bottom - inset * 0.5f),
                            Math.max(2f, radius - inset * 0.5f),
                            Math.max(2f, radius - inset * 0.5f), Path.Direction.CW);
                    ring.op(inner, Path.Op.DIFFERENCE);
                    canvas.save();
                    canvas.clipPath(ring);
                    canvas.drawBitmap(cache, src,
                            new RectF(cx - hw * kk, cy - hh * kk, cx + hw * kk, cy + hh * kk), edgeP);
                    canvas.restore();
                }
            }

            // ---------- 4. 罩白 ----------
            Paint tintP = P_TINT;
            tintP.setColor(Color.argb(Math.max(0, Math.min(255, tintAlpha)), 255, 255, 255));
            canvas.drawRect(rect, tintP);
        } else {
            Paint base = P_BASE;
            base.setColor(Color.argb(Math.min(255, tintAlpha + 40), 255, 255, 255));
            canvas.drawRect(rect, base);
        }

        // ---------- 5. 顶部镜面高光 ----------
        Paint spec = P_SPEC;
        int s1 = argbScale(0xB8, specular);
        int s2 = argbScale(0x4A, specular);
        spec.setShader(new LinearGradient(0, rect.top, 0, rect.top + h * 0.62f,
                new int[]{s1, s2, 0x00FFFFFF},
                new float[]{0f, 0.42f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(rect.left, rect.top, rect.right, rect.top + h * 0.62f, spec);

        // 上缘细亮线(最强高光)
        Paint line = P_LINE;
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeWidth(Math.max(1.4f, h * 0.028f));
        line.setColor(argbScale(0xE8, specular) << 24 | 0x00FFFFFF);
        float pad2 = Math.min(h * 0.6f, rect.width() * 0.18f);
        float ly = rect.top + Math.max(1.5f, h * 0.10f);
        canvas.drawLine(rect.left + pad2, ly, rect.right - pad2, ly, line);

        // ---------- 6. 斜向光泽 ----------
        Paint sheen = P_SHEEN;
        sheen.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                new int[]{0x00FFFFFF, 0x22FFFFFF, 0x00FFFFFF},
                new float[]{0.28f, 0.52f, 0.76f}, Shader.TileMode.CLAMP));
        canvas.drawRect(rect, sheen);

        // ---------- 7. 边缘内阴影(厚度感, 下缘更重) ----------
        Paint innerP = P_INNER;
        innerP.setShader(new LinearGradient(0, rect.top, 0, rect.bottom,
                new int[]{0x0A000000, 0x00000000, 0x1C000000},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        innerP.setStyle(Paint.Style.STROKE);
        innerP.setStrokeWidth(Math.max(1.5f, h * 0.03f));
        float pad = Math.max(1f, h * 0.015f);
        Path innerPath = new Path();
        innerPath.addRoundRect(new RectF(rect.left + pad, rect.top + pad,
                        rect.right - pad, rect.bottom - pad),
                Math.max(1f, radius - pad), Math.max(1f, radius - pad), Path.Direction.CW);
        canvas.drawPath(innerPath, innerP);

        canvas.restore();

        // ---------- 8. 描边(上亮下暗) ----------
        Paint rim = P_RIM;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1.2f, h * 0.022f));
        rim.setShader(new LinearGradient(0, rect.top, 0, rect.bottom,
                new int[]{0xF2FFFFFF, 0x8CFFFFFF, 0x5AFFFFFF, 0x3DFFFFFF},
                new float[]{0f, 0.35f, 0.72f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawPath(shape, rim);
    }

    /** 按比例缩放 alpha */
    private static int argbScale(int alpha, float scale) {
        int a = (int) (alpha * scale);
        return Math.max(0, Math.min(255, a)) << 24 | 0x00FFFFFF;
    }

    /** 增艳: 轻微提饱和 + 提亮, 让玻璃下的颜色更通透 */
    private static android.graphics.ColorFilter vibrancyFilter() {
        ColorMatrix cm = new ColorMatrix();
        float sat = 1.18f;
        float inv = 1f - sat;
        cm.set(new float[]{
                inv * 0.299f + sat, inv * 0.587f, inv * 0.114f, 0, 8,
                inv * 0.299f, inv * 0.587f + sat, inv * 0.114f, 0, 8,
                inv * 0.299f, inv * 0.587f, inv * 0.114f + sat, 0, 8,
                0, 0, 0, 1, 0
        });
        return new ColorMatrixColorFilter(cm);
    }
}
