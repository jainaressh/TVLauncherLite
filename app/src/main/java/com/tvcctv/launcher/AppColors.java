package com.tvcctv.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Picks a tile colour from each app's icon or banner, once per app (a few bytes each). Worked out on the image
 * loader's background thread and read on the main thread, hence the thread-safe map.
 */
final class AppColors {
    /** Grey or colourless icons (Settings) get a neutral charcoal. */
    static final int NEUTRAL = 0xFF4A5160;
    private static final Map<String, Integer> cache = new ConcurrentHashMap<>();

    private AppColors() {}

    /** Forgets an app's colours, e.g. after it was updated with a new icon. */
    static void forget(String pkg) {
        cache.remove(pkg);
        cache.remove(pkg + "#banner");
    }

    /** The icon's main colour: the most common colourful hue, ignoring white, black and grey. */
    static int ofIcon(String pkg, Drawable icon) {
        Integer cached = cache.get(pkg);
        if (cached != null) return cached;
        Bitmap b = render(icon, 24, 24);
        float[] hsv = new float[3];
        double[] weight = new double[12];
        double[][] sum = new double[12][3];
        for (int y = 0; y < b.getHeight(); y++) {
            for (int x = 0; x < b.getWidth(); x++) {
                int p = b.getPixel(x, y);
                if (Color.alpha(p) < 200) continue;
                Color.colorToHSV(p, hsv);
                if (hsv[1] < 0.25f || hsv[2] < 0.2f) continue;
                int bucket = Math.min(11, (int) (hsv[0] / 30f));
                double wgt = hsv[1] * hsv[2];
                weight[bucket] += wgt;
                sum[bucket][0] += Color.red(p) * wgt;
                sum[bucket][1] += Color.green(p) * wgt;
                sum[bucket][2] += Color.blue(p) * wgt;
            }
        }
        b.recycle();
        int best = 0;
        for (int i = 1; i < 12; i++) if (weight[i] > weight[best]) best = i;
        int color = weight[best] < 2 ? NEUTRAL : Color.rgb((int) (sum[best][0] / weight[best]),
                (int) (sum[best][1] / weight[best]), (int) (sum[best][2] / weight[best]));
        cache.put(pkg, color);
        return color;
    }

    /** A banner's background colour (its top-left corner), so the tile blends into the banner. */
    static int ofBanner(String pkg, Drawable banner) {
        String key = pkg + "#banner";
        Integer cached = cache.get(key);
        if (cached != null) return cached;
        Bitmap b = render(banner, 32, 18);
        int p = b.getPixel(1, 1);
        b.recycle();
        int color = Color.alpha(p) < 128 ? NEUTRAL : (p | 0xFF000000);
        cache.put(key, color);
        return color;
    }

    /** The colour adjusted for a dark-theme card: rich enough for white text on top. */
    static int[] darkCard(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.min(hsv[1], 0.85f);
        hsv[2] = Math.max(0.42f, Math.min(hsv[2], 0.78f));
        int top = Color.HSVToColor(hsv);
        hsv[2] *= 0.62f;
        return new int[]{top, Color.HSVToColor(hsv)};
    }

    /** A soft pastel of the colour, for the corner shape on light-theme cards. */
    static int pastel(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] *= 0.7f;
        hsv[2] = 1f;
        return (Color.HSVToColor(hsv) & 0x00FFFFFF) | 0xA6000000;
    }

    static boolean isLight(int color) {
        return (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000 > 160;
    }

    private static Bitmap render(Drawable d, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Rect old = d.copyBounds();
        d.setBounds(0, 0, w, h);
        d.draw(new Canvas(b));
        d.setBounds(old);
        return b;
    }
}
