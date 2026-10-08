package com.zhiyu.imgx.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 玻璃日志控制台: 等宽字体 + 关键词着色 + 进度条 + 自动滚动。
 */
public class LogView extends LinearLayout {

    private final TextView logText;
    private final TextView progressText;
    private final GradientDrawable progressFill;

    private static final Pattern ERR = Pattern.compile("失败|错误|ERROR|error|FATAL|Exception|警告|WARN");
    private static final Pattern OK = Pattern.compile("完成|成功|✅|就绪|PASS|OK|生成|已");

    public LogView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        float density = context.getResources().getDisplayMetrics().density;

        // 进度条容器
        LinearLayout barBox = new LinearLayout(context);
        barBox.setOrientation(HORIZONTAL);
        barBox.setBackground(rounded(0x33FFFFFF, 0x66FFFFFF, 6 * density));
        android.widget.FrameLayout bar = new android.widget.FrameLayout(context);
        progressFill = new GradientDrawable();
        progressFill.setColor(0xFFA78BFA);
        progressFill.setCornerRadius(6 * density);
        progressFill.setBounds(0, 0, 0, 0);
        android.view.View fillView = new android.view.View(context) {
            @Override
            protected void onDraw(android.graphics.Canvas canvas) {
                progressFill.setBounds(0, 0, getWidth(), getHeight());
                progressFill.draw(canvas);
            }
        };
        bar.addView(fillView, new android.widget.FrameLayout.LayoutParams(
                0, android.widget.FrameLayout.LayoutParams.MATCH_PARENT, 1));
        barBox.addView(bar, new LinearLayout.LayoutParams(0, (int) (6 * density), 1));
        progressText = new TextView(context);
        progressText.setTextColor(0xFFA78BFA);
        progressText.setTextSize(12);
        progressText.setText("0%");
        progressText.setGravity(android.view.Gravity.CENTER_VERTICAL);
        progressText.setPadding((int) (8 * density), 0, 0, 0);
        barBox.addView(progressText, new LinearLayout.LayoutParams(
                (int) (44 * density), (int) (6 * density)));
        addView(barBox, new LayoutParams(LayoutParams.MATCH_PARENT, (int) (8 * density)));

        // 日志文本
        logText = new TextView(context);
        logText.setTextColor(0xFF2B3238);
        logText.setTextSize(12);
        logText.setTypeface(android.graphics.Typeface.MONOSPACE);
        logText.setLineSpacing(2 * density, 1.1f);
        logText.setPadding((int) (10 * density), (int) (8 * density), (int) (10 * density), (int) (8 * density));
        logText.setBackground(rounded(0x66FFFFFF, 0x44FFFFFF, 16 * density));
        addView(logText, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));
    }

    private android.graphics.drawable.Drawable rounded(int fill, int stroke, float radius) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fill);
        gd.setCornerRadius(radius);
        gd.setStroke((int) stroke, stroke);
        return gd;
    }

    public void append(String line) {
        if (line == null) return;
        SpannableString sp = new SpannableString(line + "\n");
        int color = 0xFF2B3238;
        if (ERR.matcher(line).find()) color = 0xFFE53935;
        else if (OK.matcher(line).find()) color = 0xFF00897B;
        sp.setSpan(new ForegroundColorSpan(color), 0, line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        logText.append(sp);
        // 限制行数
        int lines = logText.getLineCount();
        if (lines > 600) {
            int end = logText.getText().toString().indexOf('\n', logText.getText().toString().length() - 20000);
            if (end > 0) logText.getEditableText().delete(0, end + 1);
        }
        post(() -> {
            if (getParent() instanceof android.widget.ScrollView) {
                ((android.widget.ScrollView) getParent()).fullScroll(android.view.View.FOCUS_DOWN);
            }
        });
    }

    public void setProgress(int percent) {
        progressText.setText(percent + "%");
        progressText.post(() -> {
            android.view.View parent = (android.view.View) progressText.getParent();
            android.view.View fillView = null;
            if (parent instanceof LinearLayout) {
                // 找到进度条视图
                android.view.View first = ((LinearLayout) parent).getChildAt(0);
                if (first instanceof android.widget.FrameLayout) {
                    fillView = ((android.widget.FrameLayout) first).getChildAt(0);
                }
            }
            if (fillView != null) {
                android.widget.FrameLayout box = (android.widget.FrameLayout) fillView.getParent();
                fillView.getLayoutParams().width = (int) (box.getWidth() * percent / 100f);
                fillView.requestLayout();
            }
        });
    }

    public void clear() {
        logText.setText("");
        progressText.setText("0%");
    }

    /** 当前日志全文(供落盘: 打包/解包完成后写入输出目录旁, 不依赖悬浮窗) */
    public String text() {
        CharSequence t = logText.getText();
        return t == null ? "" : t.toString();
    }
}
