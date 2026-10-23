package com.tvcctv.launcher;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvInputInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Launcher settings. Only in memory while open; every choice is saved to Prefs straight away. */
public final class SettingsActivity extends Activity {
    private SharedPreferences prefs;
    private LinearLayout list;
    private final List<View> rows = new ArrayList<>();
    /** The TV's inputs, read once on first render. */
    private List<TvInputInfo> inputs;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = Prefs.get(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(48), dp(27), dp(48), dp(27));
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF15171A);
        scroll.addView(list);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render(); // also picks up a wallpaper chosen in the image browser
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Settings runs in its own process: end it on the way out so its memory is freed straight away.
        if (isFinishing()) Process.killProcess(Process.myPid());
    }

    private void render() {
        int focused = rows.indexOf(getCurrentFocus());
        list.removeAllViews();
        rows.clear();

        TextView title = Ui.text(this, 26, Color.WHITE);
        title.setText("Launcher settings");
        list.addView(title);

        header("Look");
        row("Theme", Theme.MODE_NAMES[prefs.getInt(Prefs.THEME, Theme.MODE_AUTO)], () ->
                Ui.dialog(this, "Theme", Theme.MODE_NAMES, which -> {
                    prefs.edit().putInt(Prefs.THEME, which).commit();
                    render();
                }));
        row("Background", wallpaperSummary(), this::pickBackground);

        header("Top bar");
        toggle("Clock", Prefs.SHOW_CLOCK);
        toggle("Date", Prefs.SHOW_DATE);
        toggle("Search button", Prefs.SHOW_SEARCH);
        toggle("Network icon", Prefs.SHOW_NET);
        toggle("TV inputs button", Prefs.SHOW_INPUTS);
        toggle("Android settings button", Prefs.SHOW_SYS_SETTINGS);
        toggle("Launcher settings button", Prefs.SHOW_TUNE);
        note("You can always open these settings by holding Back or pressing Menu on the home screen.");

        header("Apps");
        boolean icons = prefs.getBoolean(Prefs.ICON_TILES, false);
        row("Tile style", icons ? "Icons" : "Banners", () -> {
            prefs.edit().putBoolean(Prefs.ICON_TILES, !icons).commit();
            render();
        });
        int size = Math.max(0, Math.min(Prefs.CARD_SIZES.length - 1, prefs.getInt(Prefs.CARD_SIZE, 0)));
        row("Card size", Prefs.CARD_SIZES[size] + " · " + Prefs.CARD_COLUMNS[size] + " per row", () -> {
            CharSequence[] names = new CharSequence[Prefs.CARD_SIZES.length];
            for (int i = 0; i < names.length; i++) {
                names[i] = Prefs.CARD_SIZES[i] + " · " + Prefs.CARD_COLUMNS[i] + " per row";
            }
            Ui.dialog(this, "Card size", names, which -> {
                prefs.edit().putInt(Prefs.CARD_SIZE, which).commit();
                render();
            });
        });
        toggle("Category tabs", Prefs.SHOW_TABS);
        note("Hold OK on an app to move it, add it to the quick bar or change its category. "
                + "Each tab keeps its own order.");

        header("Quick bar");
        toggle("Quick bar", Prefs.SHOW_QUICK_BAR);
        toggle("Input buttons in quick bar", Prefs.SHOW_BAR_INPUTS);

        if (inputs == null) inputs = TvInputs.list(this);
        if (!inputs.isEmpty()) {
            header("TV inputs");
            List<String> hidden = Prefs.list(prefs, Prefs.INPUTS_HIDDEN);
            for (TvInputInfo info : inputs) {
                String id = info.getId();
                CharSequence tvName = TvInputs.label(this, info);
                String custom = prefs.getString(Prefs.INPUT_NAME_PREFIX + id, "");
                String name = custom.isEmpty() ? tvName.toString() : custom + " (" + tvName + ")";
                boolean shown = !hidden.contains(id);
                row(name, shown ? "Shown" : "Hidden", () -> {
                    List<String> h = Prefs.list(prefs, Prefs.INPUTS_HIDDEN);
                    if (shown) h.add(id);
                    else h.remove(id);
                    Prefs.putList(prefs, Prefs.INPUTS_HIDDEN, h);
                    render();
                });
            }
            note("Hold OK on an input button in the quick bar to rename or move it.");
        }

        header("Startup");
        row("Open app at startup", autostartSummary(), this::pickAutostart);

        // Only two permissions need your OK; the rest (network status, internet) are granted at install.
        header("Permissions");
        boolean storage = checkSelfPermission(Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
        row("Photos and media (wallpaper images)", storage ? "Allowed" : "Not allowed",
                () -> openSettingsPage(Settings.ACTION_APPLICATION_DETAILS_SETTINGS));
        row("Install unknown apps (Downloads)",
                getPackageManager().canRequestPackageInstalls() ? "Allowed" : "Not allowed",
                () -> openSettingsPage(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES));
        note("Asked for only when needed. Press OK on a row to change it in Android settings.");

        header("About");
        row("Version", versionSummary(), () -> { });
        note("TV Launcher Lite · Android " + Build.VERSION.RELEASE + " · " + Build.MODEL);

        rows.get(Math.max(0, Math.min(focused, rows.size() - 1))).requestFocus();
    }

    /** e.g. "0.1.0 (build 100)", read from the installed app so it always matches the APK. */
    private String versionSummary() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName + " (build " + info.getLongVersionCode() + ")";
        } catch (PackageManager.NameNotFoundException e) {
            return "Unknown";
        }
    }

    private void openSettingsPage(String action) {
        try {
            startActivity(new Intent(action, Uri.fromParts("package", getPackageName(), null)));
        } catch (ActivityNotFoundException | SecurityException e) {
            Ui.toast(this, "This TV has no settings page for that");
        }
    }

    // ---- Rows -------------------------------------------------------------------------------------------------

    private void header(String text) {
        TextView t = Ui.text(this, 14, 0xFF8AB4F8);
        t.setText(text);
        t.setPadding(dp(16), dp(22), 0, dp(6));
        list.addView(t);
    }

    private void note(String text) {
        TextView t = Ui.text(this, 13, 0x99FFFFFF);
        t.setText(text);
        t.setPadding(dp(16), dp(6), dp(16), 0);
        list.addView(t);
    }

    private void row(String title, String value, Runnable onClick) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(12), dp(16), dp(12));
        r.setFocusable(true);
        r.setBackground(Ui.focusBackground(this, false));
        r.setOnClickListener(v -> onClick.run());
        TextView name = Ui.text(this, 18, Color.WHITE);
        name.setText(title);
        r.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView summary = Ui.text(this, 16, 0xB3FFFFFF);
        summary.setText(value);
        r.addView(summary);
        list.addView(r, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        rows.add(r);
    }

    private void toggle(String title, String key) {
        boolean on = Prefs.show(prefs, key);
        row(title, on ? "On" : "Off", () -> {
            prefs.edit().putBoolean(key, !on).commit();
            render();
        });
    }

    // ---- Wallpaper --------------------------------------------------------------------------------------------

    private String wallpaperSummary() {
        switch (prefs.getInt(Prefs.WP_TYPE, Prefs.WP_THEME)) {
            case Prefs.WP_BLACK:
                return "Black";
            case Prefs.WP_SOLID:
                return "Solid colour · " + Prefs.colorName(prefs.getInt(Prefs.WP_COLOR, Prefs.COLORS[0]));
            case Prefs.WP_GRADIENT:
                return "Gradient · " + prefs.getString(Prefs.WP_GNAME, Prefs.GRADIENT_NAMES[0]);
            case Prefs.WP_IMAGE:
                return "Image";
            case Prefs.WP_PHOTO:
                return "Built-in photo";
            default:
                return "Theme gradient";
        }
    }

    /** Background types, each previewed as it would look now (the current theme, current colour, and so on). */
    private void pickBackground() {
        boolean dark = Theme.resolve(prefs).dark;
        float density = getResources().getDisplayMetrics().density;
        int type = prefs.getInt(Prefs.WP_TYPE, Prefs.WP_THEME);
        int[] g = gradientInUse();
        List<Gallery.Item> items = new ArrayList<>();
        items.add(new Gallery.Item("Theme gradient", new Backdrop(dark, false, density)));
        items.add(new Gallery.Item("Built-in photo",
                thumbnail(BitmapFactory.decodeResource(getResources(), dark ? R.drawable.wp_dark : R.drawable.wp_light,
                        thumbOptions()))));
        items.add(new Gallery.Item("Black", new ColorDrawable(Color.BLACK)));
        items.add(new Gallery.Item("Solid colour", new ColorDrawable(prefs.getInt(Prefs.WP_COLOR, Prefs.COLORS[0]))));
        items.add(new Gallery.Item("Gradient", gradient(g[0], g[1], g[2])));
        File saved = new File(getFilesDir(), Prefs.WALLPAPER_FILE);
        items.add(new Gallery.Item("Image from storage", saved.exists()
                ? thumbnail(BitmapFactory.decodeFile(saved.getPath(), thumbOptions())) : addTile()));
        int[] order = {Prefs.WP_THEME, Prefs.WP_PHOTO, Prefs.WP_BLACK, Prefs.WP_SOLID, Prefs.WP_GRADIENT, Prefs.WP_IMAGE};
        int current = -1;
        for (int i = 0; i < order.length; i++) if (order[i] == type) current = i;

        Gallery.show(this, "Background", items, current, which -> {
            switch (which) {
                case 0:
                    saveWallpaper(e -> e.putInt(Prefs.WP_TYPE, Prefs.WP_THEME));
                    break;
                case 1:
                    saveWallpaper(e -> e.putInt(Prefs.WP_TYPE, Prefs.WP_PHOTO));
                    break;
                case 2:
                    saveWallpaper(e -> e.putInt(Prefs.WP_TYPE, Prefs.WP_BLACK));
                    break;
                case 3:
                    pickColor("Solid colour", type == Prefs.WP_SOLID ? prefs.getInt(Prefs.WP_COLOR, 0) : -1,
                            c -> saveWallpaper(e -> e
                            .putInt(Prefs.WP_TYPE, Prefs.WP_SOLID)
                            .putInt(Prefs.WP_COLOR, c)));
                    break;
                case 4:
                    pickGradient();
                    break;
                default:
                    startActivity(new Intent(this, ImageBrowserActivity.class));
                    break;
            }
        });
    }

    private void pickColor(String title, int current, IntConsumer done) {
        List<Gallery.Item> items = new ArrayList<>();
        int selected = -1;
        for (int i = 0; i < Prefs.COLORS.length; i++) {
            items.add(new Gallery.Item(Prefs.COLOR_NAMES[i], new ColorDrawable(Prefs.COLORS[i])));
            if (Prefs.COLORS[i] == current) selected = i;
        }
        Gallery.show(this, title, items, selected, which -> done.accept(Prefs.COLORS[which]));
    }

    private void pickGradient() {
        int presets = Prefs.GRADIENTS.length;
        boolean inUse = prefs.getInt(Prefs.WP_TYPE, Prefs.WP_THEME) == Prefs.WP_GRADIENT;
        String currentName = inUse ? prefs.getString(Prefs.WP_GNAME, "") : "";
        List<Gallery.Item> items = new ArrayList<>();
        int selected = -1;
        for (int i = 0; i < presets; i++) {
            int[] g = Prefs.GRADIENTS[i];
            items.add(new Gallery.Item(Prefs.GRADIENT_NAMES[i], gradient(g[0], g[1], g[2])));
            if (Prefs.GRADIENT_NAMES[i].equals(currentName)) selected = i;
        }
        items.add(new Gallery.Item("Custom…", addTile()));
        if ("Custom".equals(currentName)) selected = presets;
        Gallery.show(this, "Gradient", items, selected, which -> {
            if (which < presets) {
                int[] g = Prefs.GRADIENTS[which];
                saveGradient(Prefs.GRADIENT_NAMES[which], g[0], g[1], g[2]);
            } else {
                pickColor("First colour", -1, c1 -> pickColor("Second colour", -1, c2 -> pickDirection(c1, c2)));
            }
        });
    }

    /** The two chosen colours shown in each direction, so you pick the look rather than a word. */
    private void pickDirection(int c1, int c2) {
        List<Gallery.Item> items = new ArrayList<>();
        for (int d = 0; d < Prefs.DIRECTIONS.length; d++) {
            items.add(new Gallery.Item(Prefs.DIRECTION_NAMES[d], gradient(c1, c2, d)));
        }
        Gallery.show(this, "Direction", items, -1, dir -> saveGradient("Custom", c1, c2, dir));
    }

    /** {first colour, second colour, direction} of the gradient in use, or the first preset. */
    private int[] gradientInUse() {
        int[] first = Prefs.GRADIENTS[0];
        return new int[]{prefs.getInt(Prefs.WP_G1, first[0]), prefs.getInt(Prefs.WP_G2, first[1]),
                prefs.getInt(Prefs.WP_GDIR, first[2])};
    }

    private static Drawable gradient(int c1, int c2, int dir) {
        if (dir < 0 || dir >= Prefs.DIRECTIONS.length) dir = 0;
        return new GradientDrawable(Prefs.DIRECTIONS[dir], new int[]{c1, c2});
    }

    /** A dark tile with a + in the middle, for "Custom…" and "Image from storage". */
    private Drawable addTile() {
        float arm = Ui.dp(this, 16), stroke = Ui.dp(this, 4);
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            public void draw(Canvas c) {
                Rect b = getBounds();
                c.drawColor(0xFF2C323A);
                paint.setColor(0xCCFFFFFF);
                paint.setStrokeWidth(stroke);
                paint.setStrokeCap(Paint.Cap.ROUND);
                float x = b.exactCenterX(), y = b.exactCenterY();
                c.drawLine(x - arm, y, x + arm, y, paint);
                c.drawLine(x, y - arm, x, y + arm, paint);
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
                return PixelFormat.OPAQUE;
            }
        };
    }

    /** Small thumbnails (1/8 size) so the gallery stays light. */
    private static BitmapFactory.Options thumbOptions() {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 8;
        o.inScaled = false;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return o;
    }

    private Drawable thumbnail(Bitmap b) {
        return b != null ? new BitmapDrawable(getResources(), b) : new ColorDrawable(0xFF2C323A);
    }

    private void saveGradient(String name, int c1, int c2, int dir) {
        saveWallpaper(e -> e
                .putInt(Prefs.WP_TYPE, Prefs.WP_GRADIENT)
                .putInt(Prefs.WP_G1, c1)
                .putInt(Prefs.WP_G2, c2)
                .putInt(Prefs.WP_GDIR, dir)
                .putString(Prefs.WP_GNAME, name));
    }

    private void saveWallpaper(Consumer<SharedPreferences.Editor> change) {
        SharedPreferences.Editor e = prefs.edit();
        change.accept(e);
        Prefs.bumpWallpaper(prefs, e);
        e.commit();
        render();
    }

    // ---- Startup app ------------------------------------------------------------------------------------------

    private String autostartSummary() {
        String pkg = prefs.getString(Prefs.AUTOSTART, "");
        if (pkg.isEmpty()) return "None";
        PackageManager pm = getPackageManager();
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private void pickAutostart() {
        List<AppRepo.App> apps = AppRepo.load(this);
        CharSequence[] items = new CharSequence[apps.size() + 1];
        items[0] = "None";
        for (int i = 0; i < apps.size(); i++) items[i + 1] = apps.get(i).label;
        Ui.dialog(this, "Open app at startup", items, which -> {
            prefs.edit().putString(Prefs.AUTOSTART, which == 0 ? "" : apps.get(which - 1).pkg).commit();
            render();
        });
    }

    private int dp(float value) {
        return Ui.dp(this, value);
    }
}
