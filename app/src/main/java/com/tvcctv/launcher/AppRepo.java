package com.tvcctv.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Finds launchable apps and works out which category tab each one belongs to. */
final class AppRepo {
    static final String[] CATEGORIES = {"All", "Video", "Music", "Games", "Tools"};
    static final int CAT_ALL = 0, CAT_VIDEO = 1, CAT_MUSIC = 2, CAT_GAMES = 3, CAT_TOOLS = 4;

    /** Many apps don't declare a category, so fall back to well-known package name hints. */
    private static final String[] VIDEO_HINTS = {
            "youtube", "netflix", "primevideo", "amazon.avod", "hotstar", "disney", "plex", "kodi",
            "vlc", "mxtech", "jio", "zee5", "sonyliv", "hulu", "hbo", "twitch", "video"
    };
    private static final String[] MUSIC_HINTS = {"spotify", "music", "audio", "radio", "gaana", "saavn", "podcast"};
    private static final String[] GAME_HINTS = {"game", "games"};

    static final class App {
        final String pkg;
        final ComponentName component;
        final ActivityInfo info;
        final boolean leanback;
        final String label;
        final int autoCategory;

        App(ActivityInfo info, boolean leanback, String label) {
            this.pkg = info.packageName;
            this.component = new ComponentName(info.packageName, info.name);
            this.info = info;
            this.leanback = leanback;
            this.label = label;
            this.autoCategory = autoCategory(info.applicationInfo);
        }
    }

    private AppRepo() {}

    /** TV (leanback) entry points first, then phone-style ones for sideloaded apps. One entry per package. */
    static List<App> load(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, App> byPkg = new HashMap<>();
        collect(pm, byPkg, Intent.CATEGORY_LEANBACK_LAUNCHER, true);
        collect(pm, byPkg, Intent.CATEGORY_LAUNCHER, false);
        byPkg.remove(c.getPackageName());
        List<App> apps = new ArrayList<>(byPkg.values());
        Collator collator = Collator.getInstance();
        apps.sort((a, b) -> collator.compare(a.label, b.label));
        return apps;
    }

    /**
     * Every launchable screen (component name) without loading names or icons: a quick way to tell whether the
     * app list changed since load().
     */
    static Set<String> components(Context c) {
        PackageManager pm = c.getPackageManager();
        Set<String> out = new HashSet<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                if (!ri.activityInfo.packageName.equals(c.getPackageName())) {
                    out.add(ri.activityInfo.packageName + "/" + ri.activityInfo.name);
                }
            }
        }
        return out;
    }

    private static void collect(PackageManager pm, Map<String, App> out, String category, boolean leanback) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (!out.containsKey(ai.packageName)) {
                out.put(ai.packageName, new App(ai, leanback, ri.loadLabel(pm).toString()));
            }
        }
    }

    static int category(SharedPreferences p, App app) {
        return p.getInt(Prefs.CAT_PREFIX + app.pkg, app.autoCategory);
    }

    static Intent launchIntent(App app) {
        return new Intent(Intent.ACTION_MAIN)
                .addCategory(app.leanback ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
    }

    private static int autoCategory(ApplicationInfo ai) {
        switch (ai.category) {
            case ApplicationInfo.CATEGORY_GAME:
                return CAT_GAMES;
            case ApplicationInfo.CATEGORY_VIDEO:
            case ApplicationInfo.CATEGORY_IMAGE:
                return CAT_VIDEO;
            case ApplicationInfo.CATEGORY_AUDIO:
                return CAT_MUSIC;
            default:
                break;
        }
        String pkg = ai.packageName.toLowerCase(Locale.ROOT);
        if (matches(pkg, GAME_HINTS)) return CAT_GAMES;
        if (matches(pkg, MUSIC_HINTS)) return CAT_MUSIC;
        if (matches(pkg, VIDEO_HINTS)) return CAT_VIDEO;
        return CAT_TOOLS;
    }

    private static boolean matches(String pkg, String[] hints) {
        for (String h : hints) {
            if (pkg.contains(h)) return true;
        }
        return false;
    }
}
