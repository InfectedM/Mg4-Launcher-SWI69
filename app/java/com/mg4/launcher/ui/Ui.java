package com.mg4.launcher.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small view builders: the whole interface is created in code. */
public final class Ui {
    private Ui() {}

    public static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
    public static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    public static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    /** 0.4.2: "360°" label of the camera icons. */
    public static final Typeface BOLD = Typeface.create("sans-serif", Typeface.BOLD);

    /** Size of touchable controls: 1 Normal, 1.15 Large, 1.3 Very large. */
    public static float K = 1f;

    /** Size of a touchable control (dp × K). */
    public static int tp(Context c, float v) {
        return dp(c, v * K);
    }

    /** Text written on a touchable control (sp × K). */
    public static float ts(float sp) {
        return sp * K;
    }

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    public static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    /** Background with press effect (ripple) inside a rounded shape. */
    public static Drawable pressable(Context c, int color, float radiusDp, Theme t) {
        int r = dp(c, radiusDp);
        int ripple = t.day ? 0x22000000 : 0x33FFFFFF;
        return new RippleDrawable(ColorStateList.valueOf(ripple), Color.alpha(color) == 0 ? null : round(color, r), round(Color.WHITE, r));
    }

    public static TextView text(Context c, String s, float sizeSp, int color, Typeface tf) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        tv.setTextColor(color);
        tv.setTypeface(tf);
        tv.setIncludeFontPadding(false);
        return tv;
    }

    public static TextView oneLine(TextView tv) {
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        return tv;
    }

    public static ImageView icon(Context c, int res, int color, int sizeDp) {
        ImageView iv = new ImageView(c);
        iv.setImageResource(res);
        iv.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return iv;
    }

    /** Round or rounded icon button, touch area ≥ 56 px. */
    public static FrameLayout iconButton(Context c, Theme t, int res, int iconColor, int bgColor, int sizeDp, int iconDp,
                                         float radiusDp, String description, View.OnClickListener click) {
        sizeDp = Math.round(sizeDp * K);
        iconDp = Math.round(iconDp * K);
        radiusDp = radiusDp * K;
        FrameLayout f = new FrameLayout(c);
        f.setBackground(pressable(c, bgColor, radiusDp, t));
        f.setContentDescription(description);
        ImageView iv = icon(c, res, iconColor, iconDp);
        f.addView(iv, new FrameLayout.LayoutParams(dp(c, iconDp), dp(c, iconDp), Gravity.CENTER));
        f.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        f.setClickable(true);
        f.setOnClickListener(click);
        return f;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams lpWeight(int w, int h, float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.weight = weight;
        return p;
    }

    public static <T extends ViewGroup.MarginLayoutParams> T margins(T p, int l, int t, int r, int b) {
        p.setMargins(l, t, r, b);
        return p;
    }

    public static View spacer(Context c) {
        View v = new View(c);
        v.setLayoutParams(lpWeight(0, 1, 1f));
        return v;
    }

    public static View divider(Context c, int color, int heightDp) {
        View v = new View(c);
        v.setBackgroundColor(color);
        v.setLayoutParams(margins(new LinearLayout.LayoutParams(dp(c, 1), dp(c, heightDp)), dp(c, 6), 0, dp(c, 6), 0));
        return v;
    }
}
