package com.example.talktoagent;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.widget.LinearLayout;

/** Small shared presentation vocabulary for the Stitch Views, not a UI framework. */
public final class NativeCStyle {
    public static final int SURFACE = Color.rgb(12, 19, 36);
    public static final int LOW = Color.rgb(21, 27, 45);
    public static final int CARD = Color.rgb(25, 31, 49);
    public static final int LOWEST = Color.rgb(7, 13, 31);
    public static final int HIGH = Color.rgb(35, 41, 60);
    public static final int TEXT = Color.rgb(220, 225, 251);
    public static final int MUTED = Color.rgb(185, 203, 189);
    public static final int GREEN = Color.rgb(0, 245, 160);
    public static final int CYAN = Color.rgb(0, 227, 253);
    public static final int INK = Color.rgb(0, 57, 33);
    public static final int SPACE = 16;
    private NativeCStyle() {}
    public static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
    public static GradientDrawable rounded(Context context, int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(context, radius));
        return background;
    }
    public static LinearLayout card(Context context, int color) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        int space = dp(context, SPACE);
        card.setPadding(space, space, space, space);
        card.setBackground(rounded(context, color, 16));
        return card;
    }
}
