package com.zhiyu.imgx.ui;

/**
 * 液态玻璃 C 库的 Java 包装(对应 libliquidglass_jni.so)。
 *
 * 渲染流程:
 *   1. drawBackdrop  生成背景(渐变 + 柔光色块)
 *   2. blur          得到磨砂源(建议缓存, 多个面板共用)
 *   3. drawGlass     逐块绘制玻璃面板
 */
public final class LiquidGlass {

    private LiquidGlass() {}

    static {
        System.loadLibrary("liquidglass_jni");
    }

    /** 默认参数: 圆角/模糊/折射/边带/弯折/边宽 */
    public static float[] defaultGeometry(boolean topBar) {
        return new float[]{ topBar ? 23f : 28f, topBar ? 48f : 32f, 1.02f, 0.22f, 0.60f, 1f };
    }

    /** 默认参数: 上缘/镜面/投影/偏移/扩散/实体 */
    public static float[] defaultEffect(boolean dark, boolean topBar) {
        if (dark) return new float[]{ 0.32f, 0.14f, 0.22f, 6f, 16f, topBar ? 0.35f : 0f };
        return new float[]{ 0.70f, 0.35f, 0.18f, 6f, 14f, topBar ? 0.30f : 0f };
    }

    /** 默认颜色: 罩上/罩下/描边 */
    public static int[] defaultColors(boolean dark) {
        if (dark) return new int[]{ 0x1AFFFFFF, 0x08FFFFFF, 0x38FFFFFF };
        return new int[]{ 0x68FFFFFF, 0x30FFFFFF, 0xB8FFFFFF };
    }

    /** 类高斯模糊(int[] 原地修改, ARGB_8888) */
    public static native void nativeBlur(int[] px, int w, int h, int radius);

    /** 背景: 浅色渐变 + 缓慢漂移的柔光色块 */
    public static native void nativeDrawBackdrop(int[] px, int w, int h, float t, boolean dark);

    /** 绘制玻璃面板(backdrop 可为 null; 与 dst 相同则原地折射) */
    public static native void nativeDrawGlass(int[] dst, int dw, int dh,
                                              int[] backdrop, int bw, int bh,
                                              float x, float y, float w, float h,
                                              float[] geometry, float[] effect,
                                              int[] colors, int dark);

    public static native String nativeVersion();

    /* ---------------- 便捷方法 ---------------- */

    public static void blur(int[] px, int w, int h, int radius) {
        nativeBlur(px, w, h, radius);
    }

    public static void drawBackdrop(int[] px, int w, int h, float t, boolean dark) {
        nativeDrawBackdrop(px, w, h, t, dark);
    }

    public static void drawGlass(int[] dst, int dw, int dh, int[] backdrop, int bw, int bh,
                                 float x, float y, float w, float h,
                                 float[] geometry, float[] effect, int[] colors, boolean dark) {
        nativeDrawGlass(dst, dw, dh, backdrop, bw, bh, x, y, w, h,
                geometry, effect, colors, dark ? 1 : 0);
    }

}
