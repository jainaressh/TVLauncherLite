package com.tvcctv.launcher;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.widget.TextView;
import android.widget.Toast;

import java.util.function.IntConsumer;

/** Small view helpers shared by every screen. Views are built in code, so there are no layout files. */
final class Ui {
    private Ui() {}

    static int dp(Context c, float value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    /** White text with a soft shadow so it stays readable on image wallpapers. */
    static TextView text(Context c, float sp, int color) {
        TextView t = new TextView(c);
        style(t, sp, color);
        return t;
    }

    static void style(TextView t, float sp, int color) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setShadowLayer(4, 0, 1, 0x99000000);
    }

    /** Transparent until focused, then a soft fill with a white outline. */
    static StateListDrawable focusBackground(Context c, boolean oval) {
        GradientDrawable on = new GradientDrawable();
        if (oval) on.setShape(GradientDrawable.OVAL);
        else on.setCornerRadius(dp(c, 8));
        on.setColor(0x40FFFFFF);
        on.setStroke(dp(c, 2), Color.WHITE);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, on);
        return s;
    }

    static void dialog(Context c, CharSequence title, CharSequence[] items, IntConsumer onPick) {
        new AlertDialog.Builder(c, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setItems(items, (d, which) -> onPick.accept(which))
                .show();
    }

    static void toast(Context c, String message) {
        Toast.makeText(c, message, Toast.LENGTH_SHORT).show();
    }
}
