package com.tvcctv.launcher;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * App banners and icons, loaded on a background thread so the main thread (which answers the remote) never reads
 * another app's files. Each image is shrunk once to the size it is shown at (apps often ship banners several
 * times larger), about 0.3 MB per app, and kept for as long as the app is installed, so coming back Home shows
 * them at once. Every app stays cached: an evicting cache smaller than the screen's needs would reload images in
 * a loop. Call get() and forget() on the main thread only.
 */
final class AppImages {
    private static final int BANNER_WIDTH = 320; // px; a Large card is about this wide on a 1080p TV
    private static final int ICON_SIZE = 160;    // px

    static final class Entry {
        final Drawable banner; // null if the app has no TV banner
        final Drawable icon;

        Entry(Drawable banner, Drawable icon) {
            this.banner = banner;
            this.icon = icon;
        }
    }

    private final Resources res;
    private final android.content.pm.PackageManager pm;
    private final Executor background;
    private final Handler main;
    private final Runnable onLoaded;
    private final Map<String, Entry> cache = new HashMap<>();
    private final Set<String> pending = new HashSet<>();
    private boolean notifyPosted;

    /** onLoaded runs on the main thread after newly loaded images arrive (batched), to rebind the tiles. */
    AppImages(Resources res, android.content.pm.PackageManager pm, Executor background, Handler main,
              Runnable onLoaded) {
        this.res = res;
        this.pm = pm;
        this.background = background;
        this.main = main;
        this.onLoaded = onLoaded;
    }

    /** The app's images if loaded; otherwise null, and they are loaded in the background. */
    Entry get(AppRepo.App app) {
        Entry e = cache.get(app.pkg);
        if (e != null || !pending.add(app.pkg)) return e;
        background.execute(() -> {
            Entry loaded;
            try {
                Drawable banner = app.info.loadBanner(pm);
                Drawable icon = app.info.loadIcon(pm);
                // Work out the tile colours here too, so binding on the main thread finds them ready.
                AppColors.ofIcon(app.pkg, icon);
                if (banner != null) AppColors.ofBanner(app.pkg, banner);
                loaded = new Entry(banner == null ? null : shrink(banner, BANNER_WIDTH, BANNER_WIDTH * 9 / 16),
                        shrink(icon, ICON_SIZE, ICON_SIZE));
            } catch (RuntimeException ex) {
                // A broken app resource must not stop the others; the card keeps its name-only look.
                loaded = new Entry(null, null);
            }
            Entry result = loaded;
            main.post(() -> {
                pending.remove(app.pkg);
                cache.put(app.pkg, result);
                if (!notifyPosted) {
                    notifyPosted = true;
                    main.post(() -> {
                        notifyPosted = false;
                        onLoaded.run();
                    });
                }
            });
        });
        return null;
    }

    /** Drops an app's images, e.g. after it was updated with a new icon or uninstalled. */
    void forget(String pkg) {
        cache.remove(pkg);
        AppColors.forget(pkg);
    }

    /** Drops the images of every app not in pkgs (uninstalled or no longer launchable). */
    void retainOnly(Set<String> pkgs) {
        cache.keySet().retainAll(pkgs);
    }

    /** A copy for one view: a Drawable can only be shown in one place, but copies share the same bitmap. */
    static Drawable copy(Drawable d) {
        if (d == null) return null;
        Drawable.ConstantState state = d.getConstantState();
        return state != null ? state.newDrawable() : d;
    }

    /** Draws the image once into a bitmap no larger than maxW x maxH, keeping its proportions. */
    private Drawable shrink(Drawable d, int maxW, int maxH) {
        int w = d.getIntrinsicWidth(), h = d.getIntrinsicHeight();
        if (w <= 0 || h <= 0) {
            w = maxW;
            h = maxH;
        }
        float scale = Math.min(1f, Math.min(maxW / (float) w, maxH / (float) h));
        int bw = Math.max(1, Math.round(w * scale)), bh = Math.max(1, Math.round(h * scale));
        Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, bw, bh);
        d.draw(new Canvas(b));
        return new BitmapDrawable(res, b);
    }
}
