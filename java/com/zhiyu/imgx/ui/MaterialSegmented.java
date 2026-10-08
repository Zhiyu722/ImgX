package com.zhiyu.imgx.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Material 3 分段选择器: 浅灰轨道 + 白底选中段 + 蓝字强调。
 * 与 GlassSegmented 相同的 getSelectedIndex/setOnSelectedListener 接口。
 */
public class MaterialSegmented extends LinearLayout {

    public interface OnSelectedListener { void onSelected(int index); }

    private final String[] items;
    private int selected = 0;
    private OnSelectedListener listener;
    private final TextView[] labels;

    public MaterialSegmented(Context c, String[] items) {
        super(c);
        this.items = items;
        setOrientation(HORIZONTAL);
        setBackground(MaterialUI.round(c, 0xFFEFF3F7, 0, 12));
        int pad = (int) MaterialUI.dp(c, 4);
        setPadding(pad, pad, pad, pad);
        setGravity(Gravity.CENTER);

        labels = new TextView[items.length];
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            TextView tv = new TextView(c);
            tv.setText(items[i]);
            tv.setTextSize(14);
            tv.setGravity(Gravity.CENTER);
            tv.setSingleLine(true);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tv.setOnClickListener(v -> select(idx));
            addView(tv, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
            labels[i] = tv;
        }
        refresh();
    }

    public int getSelectedIndex() { return selected; }

    public void setSelectedIndex(int i) {
        if (i < 0 || i >= items.length) return;
        selected = i;
        refresh();
    }

    public void setOnSelectedListener(OnSelectedListener l) { this.listener = l; }

    private void select(int i) {
        if (i == selected) return;
        selected = i;
        refresh();
        if (listener != null) listener.onSelected(i);
    }

    private void refresh() {
        for (int i = 0; i < labels.length; i++) {
            boolean on = (i == selected);
            TextView tv = labels[i];
            tv.setTextColor(on ? MaterialUI.PRIMARY : MaterialUI.TEXT_SUB);
            tv.setTypeface(on ? Typeface.create(Typeface.DEFAULT, Typeface.BOLD) : Typeface.DEFAULT);
            tv.setBackground(on ? MaterialUI.round(getContext(), 0xFFFFFFFF, 0, 9) : null);
        }
    }
}
