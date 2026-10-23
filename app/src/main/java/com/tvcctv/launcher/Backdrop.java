package com.tvcctv.launcher;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * The theme wallpapers, drawn from a few gradients instead of an image (no bitmap in memory): a deep blue night
 * sky with a glowing arc for Dark, a pastel sky over soft mountains for Light. In overlay mode it only draws the readability
 * wash that goes over a photo: a dark fade or a white mist from the left, where the text sits.
 */
final class Backdrop extends Drawable {
    private final boolean dark, overlayOnly;
    private final float density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Shader base, glow, mist, skyMist, farFill, nearFill, arcFill, topShade;
    private final Path far = new Path(), near = new Path();

    /** Light theme mountain ridges, as points on a 320x180 grid (scaled to the screen), back then front. */
    private static final float[] FAR_RIDGE = {0, 120, 50, 85, 90, 105, 140, 60, 185, 100, 230, 70, 280, 98, 320, 80};
    private static final float[] NEAR_RIDGE = {0, 140, 60, 112, 110, 130, 170, 100, 230, 128, 280, 110, 320, 125};

    Backdrop(boolean dark, boolean overlayOnly, float density) {
        this.dark = dark;
        this.overlayOnly = overlayOnly;
        this.density = density;
    }

    @Override
    protected void onBoundsChange(Rect b) {
        if (b.isEmpty()) return; // gradients need a real size; draw() waits for one
        float w = b.width(), h = b.height();
        if (dark) {
            base = new LinearGradient(0, 0, w, h, 0xFF14386E, 0xFF070F22, Shader.TileMode.CLAMP);
            arcFill = new LinearGradient(w * 0.55f, 0, w, h, 0xFF1D4F94, 0xFF0A1A38, Shader.TileMode.CLAMP);
            mist = new LinearGradient(0, 0, w * 0.7f, 0, 0xD90A1630, 0x220A1630, Shader.TileMode.CLAMP);
            topShade = new LinearGradient(0, 0, 0, h * 0.3f, 0x990A1630, 0x000A1630, Shader.TileMode.CLAMP);
        } else {
            topShade = new LinearGradient(0, 0, 0, h * 0.32f, new int[]{0xF0F6F8FB, 0xB3F6F8FB, 0x00F6F8FB},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
            // Sky: soft blue at the top, lavender in the middle, peach near the horizon.
            base = new LinearGradient(0, 0, 0, h, new int[]{0xFFBFD8F5, 0xFFD9D3F2, 0xFFF7DCCB},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
            glow = new RadialGradient(w * 0.8f, h * 0.18f, h * 0.7f, 0x99FFF3E0, 0x00FFF3E0, Shader.TileMode.CLAMP);
            farFill = new LinearGradient(0, h * 0.33f, 0, h, 0xCC9DB6E0, 0x99C9D7F0, Shader.TileMode.CLAMP);
            nearFill = new LinearGradient(0, h * 0.55f, 0, h, 0xE6C3D2EE, 0xF2E1E8F6, Shader.TileMode.CLAMP);
            ridge(far, FAR_RIDGE, w, h);
            ridge(near, NEAR_RIDGE, w, h);
            // A light wash on the left keeps the heading readable.
            skyMist = new LinearGradient(0, 0, w * 0.5f, 0, 0x99FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
            mist = new LinearGradient(0, 0, w * 0.72f, 0, new int[]{0xF2F6F8FB, 0xB3F6F8FB, 0x00F6F8FB},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
        }
    }

    /** A smooth mountain ridge through the given points, closed along the bottom of the screen. */
    private static void ridge(Path p, float[] pts, float w, float h) {
        float sx = w / 320f, sy = h / 180f;
        p.reset();
        p.moveTo(pts[0] * sx, pts[1] * sy);
        for (int i = 2; i < pts.length; i += 2) {
            float px = pts[i - 2] * sx, py = pts[i - 1] * sy, x = pts[i] * sx, y = pts[i + 1] * sy;
            p.quadTo(px, py, (px + x) / 2f, (py + y) / 2f);
        }
        p.lineTo(pts[pts.length - 2] * sx, pts[pts.length - 1] * sy);
        p.lineTo(w, h);
        p.lineTo(0, h);
        p.close();
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        if (b.isEmpty() || base == null && !overlayOnly || mist == null && overlayOnly) return;
        float w = b.width(), h = b.height();
        if (overlayOnly) {
            // On a photo: the left wash for the heading and tiles, the top shade for the clock and date.
            fill(c, mist, b);
            fill(c, topShade, b);
            return;
        }
        if (dark) {
            fill(c, base, b);
            // A huge circle mostly off-screen to the right, its edge lit like a planet against the night.
            // Placed so the arc meets the right edge below the clock and date, keeping them easy to read.
            float cx = w * 1.08f, cy = h * 1.35f, r = h * 1.2f;
            paint.setShader(arcFill);
            c.drawCircle(cx, cy, r, paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            float[] widths = {28, 14, 6, 2};
            int[] alphas = {0x10, 0x22, 0x55, 0xFF};
            for (int i = 0; i < widths.length; i++) {
                paint.setStrokeWidth(widths[i] * density);
                paint.setColor((alphas[i] << 24) | 0x6FA8FF);
                c.drawCircle(cx, cy, r, paint);
            }
            paint.setStyle(Paint.Style.FILL);
        } else {
            paint.setColor(0xFF000000); // opaque, so the shaders below draw at their own alpha
            fill(c, base, b);
            fill(c, glow, b);
            paint.setShader(farFill);
            c.drawPath(far, paint);
            paint.setShader(nearFill);
            c.drawPath(near, paint);
            paint.setShader(null);
            fill(c, skyMist, b);
        }
    }

    private void fill(Canvas c, Shader shader, Rect b) {
        paint.setShader(shader);
        c.drawRect(b, paint);
        paint.setShader(null);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
        paint.setColorFilter(filter);
    }

    @Override
    public int getOpacity() {
        return overlayOnly ? PixelFormat.TRANSLUCENT : PixelFormat.OPAQUE;
    }
}
