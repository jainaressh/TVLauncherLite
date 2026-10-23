package com.tvcctv.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * One app card. Dark theme: filled with the app's own colour. Light theme: white, slightly raised, with a pastel
 * shape in the app's colour. Apps with a TV banner show the banner on its own background colour (in the light theme
 * only light banners; dark ones get the icon card). Focus grows the card and lifts it with a blue glow.
 */
final class Tile extends FrameLayout {
    private static final float FOCUS_SCALE = 1.08f, MOVE_SCALE = 1.12f;
    private static final int MOVE_COLOR = 0xFFFFB300; // amber

    private final ImageView image;
    private final TextView label;
    private final float radius;
    private int glow = 0xFF3D7BFF;
    private float restLift;
    private boolean moving;

    /** Grid cards are not focusable (the GridView tracks selection); quick-bar tiles are. */
    Tile(Context c, boolean focusable, float radiusDp) {
        super(c);
        setFocusable(focusable);
        radius = Ui.dp(c, radiusDp);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        setClipToOutline(true);

        image = new ImageView(c);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        addView(image, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        label = new TextView(c);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        label.setMaxLines(2);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        int pad = Ui.dp(c, 8);
        label.setPadding(pad, 0, pad, Ui.dp(c, 10));
        addView(label, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
    }

    /**
     * A full card in the app grid. img is null while the app's images are still loading: the card then shows its
     * name on a plain background, and is bound again once they arrive.
     */
    void bind(AppRepo.App app, AppImages.Entry img, boolean iconStyle, Theme t, int height) {
        setContentDescription(app.label);
        // Smaller card sizes shrink the icon, name and spacing with the card (a Large card is about 115 dp tall).
        float f = Math.max(0.5f, Math.min(1f, height / (float) Ui.dp(getContext(), 115)));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14 * Math.max(0.8f, f));
        label.setMaxLines(f > 0.8f ? 2 : 1);
        label.setPadding(Math.round(Ui.dp(getContext(), 8) * f), 0, Math.round(Ui.dp(getContext(), 8) * f),
                Math.round(Ui.dp(getContext(), 10) * f));
        Drawable banner = iconStyle || img == null ? null : img.banner;
        // In the light theme a dark banner looks like a hole in the page, so those apps get the icon card instead.
        if (banner != null && !t.dark && !AppColors.isLight(AppColors.ofBanner(app.pkg, banner))) banner = null;
        if (banner != null) {
            setBackground(new ColorDrawable(AppColors.ofBanner(app.pkg, banner)));
            int pad = Math.round(Ui.dp(getContext(), 8) * f);
            image.setPadding(pad, pad, pad, pad);
            image.setImageDrawable(AppImages.copy(banner));
            label.setVisibility(GONE);
        } else {
            Drawable icon = img == null ? null : AppImages.copy(img.icon);
            int color = icon == null ? AppColors.NEUTRAL : AppColors.ofIcon(app.pkg, img.icon);
            if (t.dark) {
                setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, AppColors.darkCard(color)));
                label.setTextColor(Color.WHITE);
                label.setShadowLayer(4, 0, 1, 0x66000000);
            } else {
                // Solid white (not glass): it stands out from the pale sky, with a soft shadow underneath.
                setBackground(new LayerDrawable(new Drawable[]{
                        new ColorDrawable(Color.WHITE), new Blob(AppColors.pastel(color))}));
                label.setTextColor(t.text);
                label.setShadowLayer(0, 0, 0, 0);
            }
            int side = Math.round(Ui.dp(getContext(), 16) * f);
            // Keep the icon above the name: room for its line(s) plus the name's bottom padding and a small gap.
            int nameSpace = Math.round(label.getTextSize() * 1.2f * label.getMaxLines())
                    + label.getPaddingBottom() + Math.round(Ui.dp(getContext(), 4) * f);
            image.setPadding(side, Math.round(Ui.dp(getContext(), 14) * f), side,
                    Math.max(nameSpace, Math.round(Ui.dp(getContext(), 48) * f)));
            image.setImageDrawable(icon);
            label.setText(app.label);
            label.setVisibility(VISIBLE);
        }
        setFocusLook(t);
    }

    /** A small quick-bar tile: just the app icon (blank while it loads). */
    void bindIcon(AppRepo.App app, AppImages.Entry img, Theme t) {
        setContentDescription(app.label);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(radius);
        bg.setColor(t.miniTile);
        setBackground(bg);
        int pad = Ui.dp(getContext(), 8);
        image.setPadding(pad, pad, pad, pad);
        image.setImageDrawable(img == null ? null : AppImages.copy(img.icon));
        label.setVisibility(GONE);
        setFocusLook(t);
    }

    private void setFocusLook(Theme t) {
        StateListDrawable ring = new StateListDrawable();
        ring.addState(new int[]{android.R.attr.state_focused}, ring(t.focus));
        ring.addState(new int[]{android.R.attr.state_selected}, ring(t.focus));
        setForeground(moving ? ring(MOVE_COLOR) : ring);
        glow = t.glow;
        // Light theme cards rest slightly raised with a soft neutral shadow; dark theme cards lie flat.
        restLift = t.dark ? 0 : Ui.dp(getContext(), 3);
        applyLift();
    }

    /** While being moved: an amber outline, a bit bigger and lifted higher, so it reads as "picked up". */
    void setMoving(boolean moving, Theme t) {
        this.moving = moving;
        setFocusLook(t);
        drawableStateChanged();
    }

    /** Focused: lifted high with a shadow in the glow colour. Otherwise: the resting lift and a neutral shadow. */
    private void applyLift() {
        boolean on = moving || isFocused() || isSelected();
        int shadow = moving ? MOVE_COLOR : on ? glow : 0x661E325A;
        setOutlineSpotShadowColor(shadow);
        setOutlineAmbientShadowColor(shadow);
        setTranslationZ(moving ? Ui.dp(getContext(), 18) : on ? Ui.dp(getContext(), 12) : restLift);
    }

    private GradientDrawable ring(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(radius);
        d.setStroke(Ui.dp(getContext(), 3), color);
        return d;
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        boolean on = isFocused() || isSelected();
        float scale = moving ? MOVE_SCALE : on ? FOCUS_SCALE : 1f;
        applyLift();
        if (getScaleX() != scale) animate().scaleX(scale).scaleY(scale).setDuration(120).start();
    }

    /** Soft pastel shape in the card's bottom-right corner (light theme). */
    private static final class Blob extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int color;

        Blob(int color) {
            this.color = color;
        }

        @Override
        protected void onBoundsChange(Rect b) {
            if (b.isEmpty()) return;
            float r = b.width() * 0.45f;
            paint.setShader(new RadialGradient(b.right - r * 0.15f, b.bottom, r, color, color & 0x00FFFFFF,
                    Shader.TileMode.CLAMP));
        }

        @Override
        public void draw(Canvas c) {
            if (paint.getShader() != null) c.drawRect(getBounds(), paint);
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
            return PixelFormat.TRANSLUCENT;
        }
    }
}
