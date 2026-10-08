package com.zhiyu.imgx.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.content.res.ColorStateList;
import android.widget.EditText;
import android.widget.TextView;

/**
 * Material 3 风格方框组件工厂(打包/解包页使用; 关于页保留玻璃设计)。
 * 石墨黑 #374151, 浅灰底 #F8F9FA, 圆角 + 细描边 + 涟漪按压反馈。
 */
public final class MaterialUI {

    public static final int PRIMARY = 0xFF374151;       // 石墨黑
    public static final int PRIMARY_DARK = 0xFF1F2937;  // 按压态
    public static final int SURFACE = 0xFFFFFFFF;       // 卡片白
    public static final int FIELD_BG = 0xFFF8F7FC;      // 输入框浅灰紫
    public static final int OUTLINE = 0xFFDCD6EE;       // 描边(淡灰)
    public static final int OUTLINE_FOCUS = PRIMARY;    // 聚焦描边
    public static final int TEXT = 0xFF1A1C1E;          // 正文
    public static final int TEXT_SUB = 0xFF6B7280;      // 次要
    public static final int SWITCH_OFF = 0xFFD9E1EA;    // 开关关
    public static final int SWITCH_ON = PRIMARY;        // 开关开

    private MaterialUI() {}

    static float dp(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    /** 圆角填充 + 描边 */
    public static GradientDrawable round(Context c, int fill, int stroke, float radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fill);
        gd.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) gd.setStroke((int) dp(c, 1), stroke);
        return gd;
    }

    /** 卡片: 白底圆角 16 + 淡描边 + 轻微投影 */
    public static GradientDrawable cardBg(Context c) {
        GradientDrawable gd = round(c, SURFACE, OUTLINE, 16);
        return gd;
    }

    /** 输入框: 浅底圆角 12, 默认 1dp 描边, 聚焦 2dp 主题色 */
    public static StateListDrawable editBg(Context c) {
        StateListDrawable sd = new StateListDrawable();
        GradientDrawable focus = round(c, FIELD_BG, OUTLINE_FOCUS, 12);
        focus.setStroke((int) dp(c, 2), OUTLINE_FOCUS);
        sd.addState(new int[]{android.R.attr.state_focused}, focus);
        sd.addState(new int[]{android.R.attr.state_hovered}, round(c, 0xFFF3F6FA, OUTLINE, 12));
        sd.addState(new int[]{}, round(c, FIELD_BG, OUTLINE, 12));
        return sd;
    }

    /** 主按钮(填充): 主题蓝胶囊 + 涟漪 */
    public static RippleDrawable filledBtnBg(Context c) {
        StateListDrawable sd = new StateListDrawable();
        sd.addState(new int[]{android.R.attr.state_pressed}, round(c, PRIMARY_DARK, 0, 24));
        sd.addState(new int[]{android.R.attr.state_enabled}, round(c, PRIMARY, 0, 24));
        sd.addState(new int[]{}, round(c, 0xFFB3DCF5, 0, 24));
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), sd, null);
    }

    /** 次按钮(描边): 透明底蓝字 + 涟漪 */
    public static RippleDrawable outlineBtnBg(Context c) {
        StateListDrawable sd = new StateListDrawable();
        sd.addState(new int[]{android.R.attr.state_pressed}, round(c, 0x1A374151, PRIMARY, 24));
        sd.addState(new int[]{}, round(c, Color.TRANSPARENT, OUTLINE, 24));
        return new RippleDrawable(ColorStateList.valueOf(0x22374151), sd, null);
    }

    /** Material 3 输入框 */
    public static EditText edit(Context c) {
        EditText et = new EditText(c);
        et.setBackground(editBg(c));
        et.setTextColor(TEXT);
        et.setTextSize(14);
        et.setHintTextColor(TEXT_SUB);
        et.setSingleLine(true);
        int pad = (int) dp(c, 14);
        et.setPadding(pad, (int) dp(c, 12), pad, (int) dp(c, 12));
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) dp(c, 14);
        et.setLayoutParams(lp);
        return et;
    }

    /** 字段标签 */
    public static TextView fieldLabel(Context c, String s) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextColor(TEXT_SUB);
        tv.setTextSize(13);
        tv.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        return tv;
    }

    /** Switch 轨道 */
    public static GradientDrawable switchTrack(Context c, boolean on) {
        return round(c, on ? SWITCH_ON : SWITCH_OFF, 0, 14);
    }

    /** Switch 圆钮(白+阴影用两层) */
    public static GradientDrawable switchThumb(Context c) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(0xFFFFFFFF);
        gd.setCornerRadius(dp(c, 10));
        gd.setStroke((int) dp(c, 1), 0x14000000);
        return gd;
    }
}
