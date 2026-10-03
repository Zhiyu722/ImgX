package com.zhiyu.imgx.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** 简易流式布局: 子视图超出宽度自动换行(用于"支持格式"标签)。 */
public class FlowLayout extends ViewGroup {

    private int hGap = 0, vGap = 0;

    public FlowLayout(Context context) {
        super(context);
    }

    public FlowLayout setGaps(int horizontalDp, int verticalDp) {
        float d = getResources().getDisplayMetrics().density;
        hGap = (int) (horizontalDp * d);
        vGap = (int) (verticalDp * d);
        return this;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int maxW = MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight();
        int x = 0, y = 0, rowH = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            measureChild(c, MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int cw = c.getMeasuredWidth(), ch = c.getMeasuredHeight();
            if (x + cw > maxW && x > 0) { x = 0; y += rowH + vGap; rowH = 0; }
            x += cw + hGap;
            rowH = Math.max(rowH, ch);
        }
        int totalH = y + rowH + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), totalH);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int maxW = getWidth() - getPaddingLeft() - getPaddingRight();
        int x = getPaddingLeft(), y = getPaddingTop(), rowH = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            int cw = c.getMeasuredWidth(), ch = c.getMeasuredHeight();
            if (x + cw > getWidth() - getPaddingRight() && x > getPaddingLeft()) {
                x = getPaddingLeft();
                y += rowH + vGap;
                rowH = 0;
            }
            c.layout(x, y, x + cw, y + ch);
            x += cw + hGap;
            rowH = Math.max(rowH, ch);
        }
    }
}
