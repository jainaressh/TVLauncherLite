package com.tvcctv.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.ExifInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Builds the home screen background and prepares picked images. */
final class Wallpaper {
    private Wallpaper() {}

    static Drawable load(Context c, boolean dark) {
        SharedPreferences p = Prefs.get(c);
        float density = c.getResources().getDisplayMetrics().density;
        switch (p.getInt(Prefs.WP_TYPE, Prefs.WP_THEME)) {
            case Prefs.WP_BLACK:
                return new ColorDrawable(Color.BLACK);
            case Prefs.WP_SOLID:
                return new ColorDrawable(p.getInt(Prefs.WP_COLOR, Prefs.COLORS[0]));
            case Prefs.WP_GRADIENT: {
                int[] fallback = Prefs.GRADIENTS[0];
                int dir = p.getInt(Prefs.WP_GDIR, fallback[2]);
                if (dir < 0 || dir >= Prefs.DIRECTIONS.length) dir = 0;
                // Drawn from two colours on the fly, so no bitmap is held in memory.
                return new GradientDrawable(Prefs.DIRECTIONS[dir], new int[]{
                        p.getInt(Prefs.WP_G1, fallback[0]), p.getInt(Prefs.WP_G2, fallback[1])});
            }
            case Prefs.WP_PHOTO: {
                // Built-in photos (see WALLPAPER_CREDITS.md), one per theme. Only the current one is in memory.
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                o.inScaled = false;
                Bitmap b = BitmapFactory.decodeResource(c.getResources(),
                        dark ? R.drawable.wp_dark : R.drawable.wp_light, o);
                if (b != null) return new LayerDrawable(new Drawable[]{
                        new BitmapDrawable(c.getResources(), b), new Backdrop(dark, true, density)});
                break;
            }
            case Prefs.WP_IMAGE: {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565; // half the memory of ARGB_8888; JPEG has no alpha anyway
                Bitmap b = BitmapFactory.decodeFile(new File(c.getFilesDir(), Prefs.WALLPAPER_FILE).getPath(), o);
                // A photo gets the theme's readability wash on the left, where the text sits.
                if (b != null) return new LayerDrawable(new Drawable[]{
                        new BitmapDrawable(c.getResources(), b), new Backdrop(dark, true, density)});
                break;
            }
            default:
                break;
        }
        return new Backdrop(dark, false, density);
    }

    /**
     * Decodes {@code src}, applies its EXIF rotation, centre-crops it to exactly {@code w x h} and saves it as the
     * wallpaper file. Done once when picked, so the launcher never has to load a large photo. Runs off the UI thread.
     */
    static boolean save(Context c, File src, int w, int h) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(src.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false;

        int rotation = rotationOf(src);
        boolean sideways = rotation == 90 || rotation == 270;
        int srcW = sideways ? bounds.outHeight : bounds.outWidth;
        int srcH = sideways ? bounds.outWidth : bounds.outHeight;
        int sample = 1;
        while (srcW / (sample * 2) >= w && srcH / (sample * 2) >= h) sample *= 2;

        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap decoded = BitmapFactory.decodeFile(src.getPath(), o);
        if (decoded == null) return false;

        if (rotation != 0) {
            Matrix m = new Matrix();
            m.postRotate(rotation);
            Bitmap rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), m, true);
            if (rotated != decoded) decoded.recycle();
            decoded = rotated;
        }

        float scale = Math.max(w / (float) decoded.getWidth(), h / (float) decoded.getHeight());
        int cropW = Math.min(decoded.getWidth(), Math.round(w / scale));
        int cropH = Math.min(decoded.getHeight(), Math.round(h / scale));
        Matrix m = new Matrix();
        m.setScale(scale, scale);
        Bitmap out = Bitmap.createBitmap(decoded, (decoded.getWidth() - cropW) / 2,
                (decoded.getHeight() - cropH) / 2, cropW, cropH, m, true);
        if (out != decoded) decoded.recycle();

        File tmp = new File(c.getFilesDir(), Prefs.WALLPAPER_FILE + ".tmp");
        boolean ok;
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            ok = out.compress(Bitmap.CompressFormat.JPEG, 90, fos);
        } catch (IOException e) {
            ok = false;
        } finally {
            out.recycle();
        }
        return ok && tmp.renameTo(new File(c.getFilesDir(), Prefs.WALLPAPER_FILE));
    }

    private static int rotationOf(File f) {
        try {
            switch (new ExifInterface(f.getPath()).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    return 90;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    return 180;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    return 270;
                default:
                    return 0;
            }
        } catch (IOException e) {
            return 0;
        }
    }
}
