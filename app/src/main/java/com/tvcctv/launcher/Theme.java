package com.tvcctv.launcher;

import android.content.SharedPreferences;

import java.util.Calendar;

/** Colours for the home screen in its dark and light looks. Only the active theme is used at a time. */
final class Theme {
    static final int MODE_AUTO = 0, MODE_DARK = 1, MODE_LIGHT = 2;
    static final String[] MODE_NAMES = {"Auto (light 7 AM – 7 PM)", "Dark", "Light"};
    /** Auto mode is light from DAY_START to NIGHT_START (24-hour clock). */
    private static final int DAY_START = 7, NIGHT_START = 19;

    static final Theme DARK = new Theme(true,
            0xFFFFFFFF, 0xB3D6E4FF, // text, secondary text
            0x590A1A33, 0x40FFFFFF, // glass tint, glass border
            0x33FFFFFF,             // small quick-bar tiles
            0xFF8AB4FF, 0xFF3D7BFF); // focus outline, focus glow
    static final Theme LIGHT = new Theme(false,
            0xFF111827, 0xFF5B6575,
            0xB8FFFFFF, 0xD9FFFFFF,
            0xFFFFFFFF,
            0xFF2F6BFF, 0xFF2F6BFF);

    final boolean dark;
    final int text, textSecondary, glassTint, glassBorder, miniTile, focus, glow;
    /** The current tab: a white pill with blue text in both themes. */
    final int tabActiveBg = 0xFFFFFFFF, tabActiveText = 0xFF1A73E8;

    private Theme(boolean dark, int text, int textSecondary, int glassTint, int glassBorder, int miniTile,
                  int focus, int glow) {
        this.dark = dark;
        this.text = text;
        this.textSecondary = textSecondary;
        this.glassTint = glassTint;
        this.glassBorder = glassBorder;
        this.miniTile = miniTile;
        this.focus = focus;
        this.glow = glow;
    }

    static Theme resolve(SharedPreferences p) {
        switch (p.getInt(Prefs.THEME, MODE_AUTO)) {
            case MODE_DARK:
                return DARK;
            case MODE_LIGHT:
                return LIGHT;
            default:
                return isNight() ? DARK : LIGHT;
        }
    }

    private static boolean isNight() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return hour < DAY_START || hour >= NIGHT_START;
    }

    /** Milliseconds until Auto mode next flips (7 AM or 7 PM). */
    static long millisToNextSwitch() {
        Calendar now = Calendar.getInstance();
        Calendar next = (Calendar) now.clone();
        next.set(Calendar.MINUTE, 0);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        int hour = now.get(Calendar.HOUR_OF_DAY);
        if (hour < DAY_START) {
            next.set(Calendar.HOUR_OF_DAY, DAY_START);
        } else if (hour < NIGHT_START) {
            next.set(Calendar.HOUR_OF_DAY, NIGHT_START);
        } else {
            next.add(Calendar.DAY_OF_YEAR, 1);
            next.set(Calendar.HOUR_OF_DAY, DAY_START);
        }
        return Math.max(1000, next.getTimeInMillis() - now.getTimeInMillis());
    }
}
