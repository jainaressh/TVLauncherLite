package com.tvcctv.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;

/** Every saved setting lives in one small SharedPreferences file. Keys and defaults are kept here. */
final class Prefs {
    static final String THEME = "theme"; // Theme.MODE_*
    static final String WP_TYPE = "wp_type";
    /**
     * WP_THEME (the default) is the drawn backdrop that matches the current Dark/Light theme; WP_PHOTO is the
     * built-in photo for the current theme.
     */
    static final int WP_BLACK = 0, WP_SOLID = 1, WP_GRADIENT = 2, WP_IMAGE = 3, WP_THEME = 4, WP_PHOTO = 5;
    static final String WP_COLOR = "wp_color";
    static final String WP_G1 = "wp_g1", WP_G2 = "wp_g2", WP_GDIR = "wp_gdir", WP_GNAME = "wp_gname";
    /** Bumped on every wallpaper change so the launcher only rebuilds the background when needed. */
    static final String WP_VERSION = "wp_ver";
    static final String WALLPAPER_FILE = "wallpaper.jpg";

    static final String SHOW_SEARCH = "show_search";
    static final String SHOW_CLOCK = "show_clock";
    static final String SHOW_DATE = "show_date";
    static final String SHOW_NET = "show_net";
    static final String SHOW_INPUTS = "show_inputs";
    static final String SHOW_SYS_SETTINGS = "show_sys_settings";
    static final String SHOW_TUNE = "show_tune";
    static final String SHOW_TABS = "show_tabs";
    /** Bottom quick bar. Keeps the old "favorites row" key so that setting carries over. */
    static final String SHOW_QUICK_BAR = "show_favs";
    static final String SHOW_BAR_INPUTS = "show_bar_inputs";

    static final String ICON_TILES = "icon_tiles";
    static final String CARD_SIZE = "card_size"; // index into CARD_SIZES
    static final String[] CARD_SIZES = {"Large", "Medium", "Small", "Extra small"};
    /** Cards per row, and rows to fit on screen, for each card size. */
    static final int[] CARD_COLUMNS = {5, 6, 7, 8};
    static final int[] CARD_ROWS = {2, 3, 3, 4};
    /** Per-tab card order: ORDER_PREFIX + AppRepo.CAT_* -> comma-separated packages. Unlisted apps follow A–Z. */
    static final String ORDER_PREFIX = "order_";
    /** TV inputs: hidden ids (comma-separated), custom names (INPUT_NAME_PREFIX + id), and order (ids). */
    static final String INPUTS_HIDDEN = "inputs_hidden";
    static final String INPUT_NAME_PREFIX = "input_name_";
    static final String INPUTS_ORDER = "inputs_order";
    /** Apps pinned to the quick bar: comma-separated package names, in display order (formerly favorites). */
    static final String FAVS = "favs";
    /** Per-package category override: CAT_PREFIX + package -> AppRepo.CAT_*. */
    static final String CAT_PREFIX = "cat_";
    static final String AUTOSTART = "autostart";
    static final String LAST_BOOT = "last_boot";

    static final GradientDrawable.Orientation[] DIRECTIONS = {
            GradientDrawable.Orientation.TOP_BOTTOM,
            GradientDrawable.Orientation.TL_BR,
            GradientDrawable.Orientation.LEFT_RIGHT,
            GradientDrawable.Orientation.BL_TR,
    };
    static final String[] DIRECTION_NAMES = {
            "Top to bottom", "Diagonal (from top left)", "Left to right", "Diagonal (from bottom left)"
    };

    static final String[] COLOR_NAMES = {
            "Charcoal", "Slate", "Navy", "Ocean blue", "Teal", "Forest",
            "Olive", "Brown", "Maroon", "Plum", "Purple", "Grey",
            "Cloud", "Sky", "Sand", "Blush" // light ones, for the light theme
    };
    static final int[] COLORS = {
            0xFF202124, 0xFF2C3E50, 0xFF0B1E3F, 0xFF1A4E8A, 0xFF0F5E56, 0xFF1E4D2B,
            0xFF4A4A1E, 0xFF4A2E1E, 0xFF5A1A1A, 0xFF5A1E4A, 0xFF3C2A6B, 0xFF5F6368,
            0xFFE6ECF3, 0xFFCADDF3, 0xFFEADCC6, 0xFFF0D9DE
    };

    static final String[] GRADIENT_NAMES = {
            "Midnight", "Ocean", "Aurora", "Sunset", "Ember", "Forest", "Grape", "Graphite"
    };
    /** {first colour, second colour, index into DIRECTIONS} */
    static final int[][] GRADIENTS = {
            {0xFF0B1E3F, 0xFF000000, 1},
            {0xFF0C447C, 0xFF04342C, 1},
            {0xFF085041, 0xFF26215C, 1},
            {0xFF993C1D, 0xFF26215C, 0},
            {0xFF4A1B0C, 0xFF000000, 0},
            {0xFF173404, 0xFF04342C, 1},
            {0xFF3C3489, 0xFF4B1528, 1},
            {0xFF444441, 0xFF000000, 0},
    };

    private Prefs() {}

    /**
     * The home screen and the settings screen run in separate processes but share this file. Calling get() re-reads
     * the file if the other process changed it (MODE_MULTI_PROCESS), and every save uses commit() so it is on disk
     * before the other process looks, rather than apply() writing it later in the background.
     */
    @SuppressWarnings("deprecation")
    static SharedPreferences get(Context c) {
        return c.getSharedPreferences("launcher", Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
    }

    /** Show/hide toggles all default to shown. */
    static boolean show(SharedPreferences p, String key) {
        return p.getBoolean(key, true);
    }

    static void bumpWallpaper(SharedPreferences p, SharedPreferences.Editor e) {
        e.putInt(WP_VERSION, p.getInt(WP_VERSION, 0) + 1);
    }

    /** A saved comma-separated list (packages or input ids). */
    static java.util.List<String> list(SharedPreferences p, String key) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String s : p.getString(key, "").split(",")) {
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    static void putList(SharedPreferences p, String key, java.util.List<String> items) {
        p.edit().putString(key, android.text.TextUtils.join(",", items)).commit();
    }

    static String colorName(int color) {
        for (int i = 0; i < COLORS.length; i++) {
            if (COLORS[i] == color) return COLOR_NAMES[i];
        }
        return "Custom";
    }
}
