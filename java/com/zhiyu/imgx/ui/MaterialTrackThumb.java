package com.zhiyu.imgx.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;

/** Material 3 Switch 的轨道/圆钮(开=主题蓝, 关=灰)。 */
public final class MaterialTrackThumb {

    private MaterialTrackThumb() {}

    /** 轨道: 开→蓝, 关→灰 */
    public static StateListDrawable track(Context c) {
        StateListDrawable sd = new StateListDrawable();
        sd.addState(new int[]{android.R.attr.state_checked},
                MaterialUI.round(c, MaterialUI.SWITCH_ON, 0, 14));
        sd.addState(new int[]{},
                MaterialUI.round(c, MaterialUI.SWITCH_OFF, 0, 14));
        return sd;
    }

    /** 圆钮: 白色带淡描边 */
    public static StateListDrawable thumb(Context c) {
        StateListDrawable sd = new StateListDrawable();
        sd.addState(new int[]{android.R.attr.state_checked},
                MaterialUI.switchThumb(c));
        sd.addState(new int[]{},
                MaterialUI.switchThumb(c));
        return sd;
    }
}
