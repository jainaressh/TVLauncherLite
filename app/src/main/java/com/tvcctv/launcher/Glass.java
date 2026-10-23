package com.tvcctv.launcher;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * Frosted glass without live blur (Android 11 can't blur cheaply): when the wallpaper is set, a tiny blurred copy
 * of it is made once (1/8 size, about 130 KB). Glass panels draw the part of that copy that sits behind them,
 * then a tint and a thin border on top.
 */
final class Glass {
    private static final int SCALE = 8;
    // Built on a background thread, read while drawing on the main thread.
    private static volatile Bitmap backdrop;
    private static volatile int screenW = 1, screenH = 1;

    private Glass() {}

    static void update(Drawable wallpaper, int w, int h) {
        screenW = Math.max(1, w);
        screenH = Math.max(1, h);
        Bitmap b = Bitmap.createBitmap(Math.max(1, w / SCALE), Math.max(1, h / SCALE), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.scale(1f / SCALE, 1f / SCALE);
        Rect old = wallpaper.copyBounds();
        wallpaper.setBounds(0, 0, w, h);
        wallpaper.draw(c);
        wallpaper.setBounds(old);
        blur(b);
        backdrop = b;
    }

    /** Not recycled: views may still have it in their last drawn frame. It is small and simply garbage-collected. */
    static void release() {
        backdrop = null;
    }

    /** Glass background for a view. radius < 0 makes a circle (or pill). */
    static Drawable panel(View owner, int tint, int border, float radius) {
        return new Panel(owner, tint, border, radius);
    }

    /** Three passes of a small box blur look close to a Gaussian blur. */
    private static void blur(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h], tmp = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            boxBlur(px, tmp, w, h, 2, true);
            boxBlur(tmp, px, w, h, 2, false);
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static void boxBlur(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w, len = horizontal ? w : h;
        for (int line = 0; line < lines; line++) {
            for (int i = 0; i < len; i++) {
                int a = 0, rr = 0, g = 0, bb = 0, n = 0;
                for (int k = Math.max(0, i - r); k <= Math.min(len - 1, i + r); k++) {
                    int p = horizontal ? in[line * w + k] : in[k * w + line];
                    a += p >>> 24;
                    rr += (p >> 16) & 0xFF;
                    g += (p >> 8) & 0xFF;
                    bb += p & 0xFF;
                    n++;
                }
                int idx = horizontal ? line * w + i : i * w + line;
                out[idx] = ((a / n) << 24) | ((rr / n) << 16) | ((g / n) << 8) | (bb / n);
            }
        }
    }

    private static final class Panel extends Drawable {
        private final View owner;
        private final float radius;
        private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Matrix matrix = new Matrix();
        private final int[] location = new int[2];
        private Bitmap shaderFor;

        Panel(View owner, int tintColor, int borderColor, float radius) {
            this.owner = owner;
            this.radius = radius;
            tint.setColor(tintColor);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(owner.getResources().getDisplayMetrics().density);
            border.setColor(borderColor);
        }

        @Override
        public void draw(Canvas c) {
            rect.set(getBounds());
            float r = radius < 0 ? Math.min(rect.width(), rect.height()) / 2f : radius;
            Bitmap b = backdrop;
            if (b != null) {
                if (shaderFor != b) {
                    image.setShader(new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                    shaderFor = b;
                }
                // Line the blurred wallpaper up with where this view is on screen.
                owner.getLocationInWindow(location);
                matrix.setScale(screenW / (float) b.getWidth(), screenH / (float) b.getHeight());
                matrix.postTranslate(-location[0], -location[1]);
                image.getShader().setLocalMatrix(matrix);
                c.drawRoundRect(rect, r, r, image);
            }
            c.drawRoundRect(rect, r, r, tint);
            if (border.getColor() != 0) {
                float half = border.getStrokeWidth() / 2f;
                rect.inset(half, half);
                c.drawRoundRect(rect, r, r, border);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            tint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            tint.setColorFilter(filter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
