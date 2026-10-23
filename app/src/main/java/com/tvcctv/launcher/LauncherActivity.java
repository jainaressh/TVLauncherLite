package com.tvcctv.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.ChangedPackages;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.FocusFinder;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home screen: top bar (category tabs, search, inputs, settings, network, clock), a big heading, the app grid
 * and the bottom quick bar. Everything is drawn in the current Dark or Light theme (see Theme).
 */
public final class LauncherActivity extends Activity {
    private static final int SIDE_PADDING_DP = 48; // TV overscan-safe margins (~5%)
    private static final int TOP_PADDING_DP = 27;
    private static final int GRID_PADDING_DP = 20; // room for a focused tile to grow and glow
    private static final int TILE_SPACING_DP = 14;
    private static final int BAR_ITEM_DP = 48;     // height of quick-bar tiles and input buttons
    private static final int[] TAB_ICONS = {
            R.drawable.ic_home, R.drawable.ic_play, R.drawable.ic_music, R.drawable.ic_games, R.drawable.ic_tools};
    private static final String[] HEADINGS = {"All apps", "Video", "Music", "Games", "Tools"};
    private static final String[] SUBHEADINGS = {
            "Everything you love, in one place", "Movies, shows and live TV", "Songs, radio and podcasts",
            "Play on the big screen", "Settings, files and utilities"};

    private SharedPreferences prefs;
    private PackageManager pm;
    private ConnectivityManager connectivity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    /**
     * Anything slow (the app list, app images, TV inputs, the wallpaper) runs here, one job at a time, so the main
     * thread stays free to answer the remote. Results are posted back to the main thread.
     */
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private AppImages images;

    private final List<AppRepo.App> allApps = new ArrayList<>();
    private final List<AppRepo.App> gridApps = new ArrayList<>();
    private final AppAdapter adapter = new AppAdapter();
    private List<InputItem> inputs = new ArrayList<>();
    private Theme theme = Theme.DARK;
    private int category = AppRepo.CAT_ALL;
    private int wallpaperKey = -1;
    private boolean wallpaperIsPhoto;
    private boolean iconTiles;
    private int cardSize; // index into Prefs.CARD_SIZES
    private int tileHeight;
    private boolean appsLoaded;
    /** Fingerprint of the settings the screen was last styled with (see onStart). */
    private int appliedSettings;
    /** Android's app-change counter at the last app-list load (read on the background thread). */
    private volatile int packageSeq;
    /** The launchable screens the app list was last loaded from (only used on the background thread). */
    private Set<String> loadedComponents = new HashSet<>();

    /** Move mode (see startMove): what is being moved, its key, and the saved order to go back to on Back. */
    private static final int MOVE_NONE = 0, MOVE_GRID = 1, MOVE_PINS = 2, MOVE_INPUTS = 3;
    private static final String MOVE_HINT = "Arrows to move · OK to place · Back to cancel";
    private int moveMode = MOVE_NONE;
    private String movingKey, moveOrderKey, moveSaved;

    private LinearLayout tabs, quickBar, pinnedRow, inputsRow;
    private View inputsDivider, pinnedDivider;
    private ImageView searchButton, inputsButton, settingsButton, tuneButton, networkIcon, downloadsButton, addButton;
    private TextClock clock, date;
    private TextView heading, subheading;
    private GridView grid;

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            loadApps(true);
        }
    };

    // Shows the TV's connection, asking about the specific network rather than getActiveNetwork(). Just after
    // boot Android briefly blocks our app's network access: getActiveNetwork() then returns null and the
    // capability callbacks may be skipped, so the unblock (onBlockedStatusChanged) is used to refresh as well.
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network n) {
            showNetwork(connectivity.getNetworkCapabilities(n));
        }

        @Override
        public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) {
            showNetwork(caps);
        }

        @Override
        public void onBlockedStatusChanged(Network n, boolean blocked) {
            showNetwork(connectivity.getNetworkCapabilities(n));
        }

        @Override
        public void onLost(Network n) {
            showNetwork(null);
        }
    };

    /** In Auto mode, flips between Light and Dark at 7 AM / 7 PM while the home screen is showing. */
    private final Runnable themeTick = new Runnable() {
        @Override
        public void run() {
            applySettings();
            refreshLists();
            handler.postDelayed(this, Theme.millisToNextSwitch());
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = Prefs.get(this);
        pm = getPackageManager();
        connectivity = getSystemService(ConnectivityManager.class);
        images = new AppImages(getResources(), pm, background, handler, this::refreshLists);
        setContentView(buildUi());
        grid.requestFocus();
        openAppAfterBoot();
    }

    @Override
    protected void onDestroy() {
        background.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onStart() {
        super.onStart();
        prefs = Prefs.get(this); // picks up changes saved by the settings screen (another process)
        // Restyling and re-laying-out the screen is the slow part of coming back Home, so it is only redone when
        // a setting (or the Auto theme) changed. Otherwise only a released photo wallpaper is rebuilt.
        int settings = prefs.getAll().hashCode() * 31 + (Theme.resolve(prefs).dark ? 1 : 0);
        if (settings != appliedSettings) {
            appliedSettings = settings;
            applySettings();
            refreshLists(); // pins, categories or tile style may have changed; images come from the cache
        } else {
            ensureWallpaper();
        }
        loadInputs();
        loadApps(false);

        // Listeners only live while the home screen is visible: nothing runs in the background.
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packageReceiver, f);
        // Offline until the callback reports the current default network (it does so right after registering).
        showNetwork(null);
        if (connectivity != null) {
            connectivity.registerDefaultNetworkCallback(networkCallback, new Handler(Looper.getMainLooper()));
        }
        if (prefs.getInt(Prefs.THEME, Theme.MODE_AUTO) == Theme.MODE_AUTO) {
            handler.postDelayed(themeTick, Theme.millisToNextSwitch());
        }
    }

    @Override
    protected void onStop() {
        // Leaving mid-move (e.g. the Home button) keeps the item where it was put; each step is already saved.
        if (moveMode != MOVE_NONE) endMove(true);
        unregisterReceiver(packageReceiver);
        if (connectivity != null) connectivity.unregisterNetworkCallback(networkCallback);
        handler.removeCallbacks(themeTick);
        super.onStop();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level < TRIM_MEMORY_UI_HIDDEN) return;
        // Another app is in front. A photo wallpaper (about 4 MB) is let go and rebuilt in onStart(). App images
        // stay cached (AppImages, about 0.3 MB per app) and the drawn gradients cost almost nothing, so returning
        // Home shows them at once.
        if (!wallpaperIsPhoto) return;
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        Glass.release();
        wallpaperKey = -1;
        wallpaperIsPhoto = false;
    }

    // ---- Keys -------------------------------------------------------------------------------------------------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            openLauncherSettings();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            event.startTracking(); // so holding Back reaches onKeyLongPress
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            openLauncherSettings(); // works on every remote, even with the settings button hidden
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        // Home screen: there is nothing to go back to.
        return keyCode == KeyEvent.KEYCODE_BACK || super.onKeyUp(keyCode, event);
    }

    // ---- Layout -----------------------------------------------------------------------------------------------

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(SIDE_PADDING_DP - GRID_PADDING_DP), dp(TOP_PADDING_DP),
                dp(SIDE_PADDING_DP - GRID_PADDING_DP), 0);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(GRID_PADDING_DP), 0, dp(GRID_PADDING_DP), 0);
        tabs = new LinearLayout(this);
        tabs.setClipChildren(false); // a focused tab grows past the row's edges
        bar.setClipChildren(false);
        for (int i = 0; i < AppRepo.CATEGORIES.length; i++) tabs.addView(tab(i));
        bar.addView(tabs);
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        searchButton = roundButton(R.drawable.ic_search, "Search", v -> openSearch());
        inputsButton = roundButton(R.drawable.ic_input, "TV inputs", v -> showInputs());
        settingsButton = roundButton(R.drawable.ic_settings, "Settings",
                v -> start(new Intent(Settings.ACTION_SETTINGS)));
        tuneButton = roundButton(R.drawable.ic_tune, "Launcher settings", v -> openLauncherSettings());
        bar.addView(searchButton);
        bar.addView(inputsButton);
        bar.addView(settingsButton);
        bar.addView(tuneButton);
        networkIcon = new ImageView(this);
        LinearLayout.LayoutParams netLp = new LinearLayout.LayoutParams(dp(26), dp(26));
        netLp.leftMargin = dp(16);
        bar.addView(networkIcon, netLp);

        LinearLayout clocks = new LinearLayout(this);
        clocks.setOrientation(LinearLayout.VERTICAL);
        clocks.setGravity(Gravity.END);
        clock = new TextClock(this);
        Ui.style(clock, 24, Color.WHITE);
        clock.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        date = new TextClock(this);
        date.setFormat12Hour("EEE, d MMM");
        date.setFormat24Hour("EEE, d MMM");
        Ui.style(date, 15, Color.WHITE);
        clocks.addView(clock);
        clocks.addView(date);
        LinearLayout.LayoutParams clockLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clockLp.leftMargin = dp(18);
        bar.addView(clocks, clockLp);
        root.addView(bar);

        heading = Ui.text(this, 34, Color.WHITE);
        heading.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        heading.setPadding(dp(GRID_PADDING_DP), dp(14), 0, 0);
        root.addView(heading);
        subheading = Ui.text(this, 17, Color.WHITE);
        subheading.setPadding(dp(GRID_PADDING_DP), 0, 0, dp(4));
        root.addView(subheading);

        grid = new GridView(this);
        grid.setAdapter(adapter);
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT)); // tiles draw their own focus outline
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(dp(TILE_SPACING_DP));
        grid.setVerticalSpacing(dp(TILE_SPACING_DP));
        grid.setPadding(dp(GRID_PADDING_DP + 4), dp(14), dp(GRID_PADDING_DP + 4), dp(22));
        grid.setClipToPadding(false);
        grid.setVerticalScrollBarEnabled(false);
        // A row cut off at the top or bottom fades out softly instead of showing a hard edge. The fade only
        // appears on a side with more rows to scroll to, and spans the bottom gap so complete rows stay sharp.
        grid.setVerticalFadingEdgeEnabled(true);
        grid.setFadingEdgeLength(dp(22));
        // Once the grid's height is known (or changes, e.g. the subtitle is hidden), fit the card size's rows to it.
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t != ob - ot) grid.post(this::sizeCards);
        });
        grid.setOnItemClickListener((parent, view, pos, id) -> launch(gridApps.get(pos)));
        grid.setOnItemLongClickListener((parent, view, pos, id) -> {
            showAppMenu(gridApps.get(pos));
            return true;
        });
        // Re-layout so the selected tile shrinks back when focus leaves the grid.
        grid.setOnFocusChangeListener((v, hasFocus) -> grid.requestLayout());
        // Frosted cards show the blurred wallpaper behind them; redraw them as they scroll so it lines up.
        grid.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int state) {
            }

            @Override
            public void onScroll(AbsListView view, int first, int visible, int total) {
                if (theme.dark) return;
                for (int i = 0; i < view.getChildCount(); i++) view.getChildAt(i).invalidate();
            }
        });
        root.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(buildQuickBar());

        // Like Google TV: moving along the tab row switches category, but entering the row from below
        // lands on the current tab instead of whichever tab happens to be nearest.
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
            if (newFocus == null || newFocus.getParent() != tabs) return;
            int index = tabs.indexOfChild(newFocus);
            if (oldFocus != null && oldFocus.getParent() == tabs) selectCategory(index);
            else if (index != category) tabs.getChildAt(category).requestFocus();
        });
        return root;
    }

    /** Bottom frosted bar: one-press TV input buttons, Downloads, then pinned apps (+ to add more). */
    private View buildQuickBar() {
        quickBar = new LinearLayout(this) {
            // Left/Right stay inside the bar and stop at its ends, instead of jumping up to the tabs or top icons.
            @Override
            public View focusSearch(View focused, int direction) {
                if (direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT) {
                    View next = FocusFinder.getInstance().findNextFocus(this, focused, direction);
                    return next != null ? next : focused;
                }
                return super.focusSearch(focused, direction);
            }
        };
        quickBar.setGravity(Gravity.CENTER_VERTICAL);
        quickBar.setPadding(dp(14), dp(12), dp(14), dp(12));
        // Let focused items grow and cast shadows into the bar's padding; by default it would cut them off there.
        quickBar.setClipChildren(false);
        quickBar.setClipToPadding(false);

        // Order: TV inputs | Downloads | pinned apps and +. The inputs and their divider hide on TVs without inputs.
        inputsRow = new LinearLayout(this);
        inputsRow.setClipChildren(false);
        quickBar.addView(inputsRow, withRoom(inputsRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), dp(8), dp(8), dp(8)));
        inputsDivider = divider();
        quickBar.addView(inputsDivider);

        // The Downloads screen (it runs in its own process, see DownloadsActivity).
        downloadsButton = squareButton(R.drawable.ic_download, "Downloads",
                v -> start(new Intent(this, DownloadsActivity.class)));
        quickBar.addView(downloadsButton);
        pinnedDivider = divider();
        quickBar.addView(pinnedDivider);

        // Pinned apps scroll sideways if there are more than fit; the + button stays at their end.
        LinearLayout pinnedArea = new LinearLayout(this);
        pinnedArea.setGravity(Gravity.CENTER_VERTICAL);
        pinnedArea.setClipChildren(false);
        pinnedRow = new LinearLayout(this);
        pinnedRow.setClipChildren(false);
        pinnedArea.addView(pinnedRow);
        addButton = squareButton(R.drawable.ic_add, "Add an app to the quick bar", v -> pickAppToPin());
        pinnedArea.addView(addButton);
        HorizontalScrollView pinnedScroll = new HorizontalScrollView(this);
        pinnedScroll.setHorizontalScrollBarEnabled(false);
        pinnedScroll.setClipChildren(false);
        pinnedScroll.addView(pinnedArea);
        // Extra room below for the tiles' shadow, which is cast downwards.
        quickBar.addView(pinnedScroll, withRoom(pinnedScroll,
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f), dp(8), dp(10), dp(12)));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(GRID_PADDING_DP);
        lp.rightMargin = dp(GRID_PADDING_DP);
        lp.bottomMargin = dp(TOP_PADDING_DP);
        quickBar.setLayoutParams(lp);
        return quickBar;
    }

    /**
     * Gives a group of quick-bar items room inside its own edges for a focused item to grow and cast its shadow,
     * so it never depends on the groups around it not clipping (the pinned apps also scroll sideways). The
     * padding adds the room; the equal negative margins keep the items exactly where they were.
     */
    private static LinearLayout.LayoutParams withRoom(ViewGroup group, LinearLayout.LayoutParams lp,
                                                      int side, int top, int bottom) {
        group.setPadding(side, top, side, bottom);
        group.setClipToPadding(false);
        lp.setMargins(-side, -top, -side, -bottom);
        return lp;
    }

    /** Thin vertical line between quick-bar groups (coloured in applyTheme). */
    private View divider() {
        View v = new View(this);
        // Items before it already have a 10dp right margin; 8 + 10 keeps it centred between groups.
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(1), dp(32));
        lp.leftMargin = dp(8);
        lp.rightMargin = dp(18);
        v.setLayoutParams(lp);
        return v;
    }

    /** One-press button that switches straight to a TV input. */
    private TextView inputButton(InputItem input) {
        TextView b = Ui.text(this, 15, theme.text);
        if (!theme.dark) b.setShadowLayer(0, 0, 0, 0);
        b.setText(displayName(input));
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(18), 0, dp(18), 0);
        b.setFocusable(true);
        GradientDrawable rest = new GradientDrawable();
        rest.setCornerRadius(dp(12));
        rest.setColor(theme.miniTile);
        b.setBackground(rest);
        if (moveMode == MOVE_INPUTS && input.info.getId().equals(movingKey)) {
            // Picked up: the same amber outline as a card being moved.
            GradientDrawable ring = new GradientDrawable();
            ring.setCornerRadius(dp(12));
            ring.setStroke(dp(3), 0xFFFFB300);
            b.setForeground(ring);
        } else {
            b.setForeground(focusRing(dp(12)));
        }
        b.setOnClickListener(v -> openInput(input.info));
        b.setOnLongClickListener(v -> {
            showInputMenu(input);
            return true;
        });
        growOnFocus(b, 1.08f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(BAR_ITEM_DP));
        lp.rightMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    private TextView tab(int cat) {
        TextView t = Ui.text(this, 15, Color.WHITE);
        t.setText(AppRepo.CATEGORIES[cat]);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(13), dp(8), dp(15), dp(8));
        Drawable icon = getDrawable(TAB_ICONS[cat]);
        icon.setBounds(0, 0, dp(20), dp(20));
        t.setCompoundDrawablesRelative(icon, null, null, null);
        t.setCompoundDrawablePadding(dp(7));
        t.setFocusable(true);
        t.setOnClickListener(v -> selectCategory(cat));
        // The current tab is already a white pill, so focus on it shows as a slight grow.
        growOnFocus(t, 1.1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    /** Round frosted button in the top bar (search, inputs, settings). */
    private ImageView roundButton(int icon, String description, View.OnClickListener onClick) {
        ImageView b = new ImageView(this);
        b.setImageResource(icon);
        b.setContentDescription(description);
        b.setFocusable(true);
        b.setOnClickListener(onClick);
        b.setPadding(dp(10), dp(10), dp(10), dp(10));
        growOnFocus(b, 1.1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(42), dp(42));
        lp.leftMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    /** Rounded-square button in the quick bar (Downloads, +). */
    private ImageView squareButton(int icon, String description, View.OnClickListener onClick) {
        ImageView b = new ImageView(this);
        b.setImageResource(icon);
        b.setContentDescription(description);
        b.setFocusable(true);
        b.setOnClickListener(onClick);
        b.setPadding(dp(12), dp(12), dp(12), dp(12));
        growOnFocus(b, 1.08f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(BAR_ITEM_DP), dp(BAR_ITEM_DP));
        lp.rightMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    private static void growOnFocus(View v, float scale) {
        v.setOnFocusChangeListener((view, hasFocus) ->
                view.animate().scaleX(hasFocus ? scale : 1f).scaleY(hasFocus ? scale : 1f).setDuration(120).start());
    }

    /** Outline shown only while focused. radius < 0 makes a circle. */
    private StateListDrawable focusRing(float radius) {
        GradientDrawable ring = new GradientDrawable();
        if (radius < 0) ring.setShape(GradientDrawable.OVAL);
        else ring.setCornerRadius(radius);
        ring.setStroke(dp(3), theme.focus);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, ring);
        return s;
    }

    // ---- Theme ------------------------------------------------------------------------------------------------

    /** Colours every fixed part of the screen for the current theme. App tiles pick it up when they are bound. */
    private void applyTheme() {
        Theme t = theme;
        ColorStateList tabColors = new ColorStateList(
                new int[][]{{android.R.attr.state_activated}, {}}, new int[]{t.tabActiveText, t.text});
        for (int i = 0; i < tabs.getChildCount(); i++) {
            TextView tab = (TextView) tabs.getChildAt(i);
            tab.setTextColor(tabColors);
            tab.setCompoundDrawableTintList(tabColors);
            shadow(tab);
            GradientDrawable current = new GradientDrawable();
            current.setCornerRadius(dp(24));
            current.setColor(t.tabActiveBg);
            GradientDrawable focused = new GradientDrawable();
            focused.setCornerRadius(dp(24));
            focused.setColor(t.dark ? 0x33FFFFFF : 0x99FFFFFF);
            focused.setStroke(dp(2), t.focus);
            StateListDrawable bg = new StateListDrawable();
            bg.addState(new int[]{android.R.attr.state_activated}, current);
            bg.addState(new int[]{android.R.attr.state_focused}, focused);
            tab.setBackground(bg);
        }

        ColorStateList iconTint = ColorStateList.valueOf(t.text);
        for (ImageView b : new ImageView[]{searchButton, inputsButton, settingsButton, tuneButton}) {
            b.setImageTintList(iconTint);
            b.setBackground(Glass.panel(b, t.glassTint, t.glassBorder, -1));
            b.setForeground(focusRing(-1));
        }
        networkIcon.setImageTintList(iconTint);
        for (ImageView b : new ImageView[]{downloadsButton, addButton}) {
            b.setImageTintList(iconTint);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(12));
            bg.setColor(t.miniTile);
            b.setBackground(bg);
            b.setForeground(focusRing(dp(12)));
        }

        clock.setTextColor(t.text);
        date.setTextColor(t.textSecondary);
        heading.setTextColor(t.text);
        subheading.setTextColor(t.dark ? t.textSecondary : 0xFF4B6BA8);
        for (TextView v : new TextView[]{clock, date, heading, subheading}) shadow(v);

        quickBar.setBackground(Glass.panel(quickBar, t.glassTint, t.glassBorder, dp(20)));
        int line = t.dark ? 0x4DFFFFFF : 0x33111827;
        inputsDivider.setBackgroundColor(line);
        pinnedDivider.setBackgroundColor(line);
    }

    /** Soft text shadow on the dark theme only; dark text on light backgrounds looks smudged with one. */
    private void shadow(TextView v) {
        if (theme.dark) v.setShadowLayer(4, 0, 1, 0x99000000);
        else v.setShadowLayer(0, 0, 0, 0);
    }

    // ---- State ------------------------------------------------------------------------------------------------

    private void applySettings() {
        theme = Theme.resolve(prefs);
        ensureWallpaper();
        applyTheme();

        setShown(searchButton, Prefs.SHOW_SEARCH);
        setShown(clock, Prefs.SHOW_CLOCK);
        setShown(date, Prefs.SHOW_DATE);
        setShown(networkIcon, Prefs.SHOW_NET);
        setShown(settingsButton, Prefs.SHOW_SYS_SETTINGS);
        setShown(tuneButton, Prefs.SHOW_TUNE);
        setShown(quickBar, Prefs.SHOW_QUICK_BAR);
        showInputButtons(); // the last known inputs at once; loadInputs() updates them if they changed

        boolean showTabs = Prefs.show(prefs, Prefs.SHOW_TABS);
        tabs.setVisibility(showTabs ? View.VISIBLE : View.GONE);
        if (!showTabs) category = AppRepo.CAT_ALL;
        markTabs();

        iconTiles = prefs.getBoolean(Prefs.ICON_TILES, false);
        cardSize = Math.max(0, Math.min(Prefs.CARD_SIZES.length - 1, prefs.getInt(Prefs.CARD_SIZE, 0)));
        grid.setNumColumns(Prefs.CARD_COLUMNS[cardSize]);
        // Smaller cards need the room of the subtitle line to fit their extra rows.
        subheading.setVisibility(cardSize == 0 ? View.VISIBLE : View.GONE);
        sizeCards();
    }

    /**
     * Card height for the current size: normally 72% of the card's width, but flatter when needed so the size's
     * rows (2 for Large, up to 4 for Extra small) fit the grid's height. Rerun when the grid's height is known.
     */
    private void sizeCards() {
        int columns = Prefs.CARD_COLUMNS[cardSize], rows = Prefs.CARD_ROWS[cardSize];
        int spacing = dp(TILE_SPACING_DP);
        int width = getResources().getDisplayMetrics().widthPixels - 2 * dp(SIDE_PADDING_DP) - 2 * dp(4);
        int tileWidth = (width - spacing * (columns - 1)) / columns;
        int height = tileWidth * 72 / 100;
        int gridSpace = grid.getHeight() - grid.getPaddingTop() - grid.getPaddingBottom();
        if (gridSpace > 0) {
            int fit = (gridSpace - spacing * (rows - 1)) / rows;
            height = Math.max(tileWidth / 2, Math.min(height, fit)); // never flatter than 2:1
        }
        if (height != tileHeight) {
            tileHeight = height;
            adapter.notifyDataSetChanged();
        }
    }

    private void setShown(View v, String key) {
        v.setVisibility(Prefs.show(prefs, key) ? View.VISIBLE : View.GONE);
    }

    /** Rebuilds the wallpaper if it changed in settings, the theme flipped, or a photo was released while hidden. */
    private void ensureWallpaper() {
        int key = prefs.getInt(Prefs.WP_VERSION, 0) * 2 + (theme.dark ? 1 : 0);
        if (key == wallpaperKey) return;
        wallpaperKey = key;
        loadWallpaper(key, theme.dark);
    }

    /**
     * Builds the wallpaper and its blurred copy (for the frosted glass) on the background thread; a photo takes a
     * noticeable moment to decode on a TV chip. The screen keeps its current background until the new one is ready.
     */
    private void loadWallpaper(int key, boolean dark) {
        Rect screen = getWindowManager().getCurrentWindowMetrics().getBounds();
        background.execute(() -> {
            Drawable wallpaper = Wallpaper.load(this, dark);
            Glass.update(wallpaper, screen.width(), screen.height());
            handler.post(() -> {
                if (key != wallpaperKey) return; // settings changed again meanwhile; a newer one is coming
                // The wallpaper is the window background, so nothing extra is drawn underneath it.
                getWindow().setBackgroundDrawable(wallpaper);
                wallpaperIsPhoto = wallpaper instanceof LayerDrawable; // photos come with a readability overlay
                // The frosted glass draws the new blurred copy.
                quickBar.invalidate();
                for (View v : new View[]{searchButton, inputsButton, settingsButton, tuneButton}) v.invalidate();
            });
        });
    }

    /** TV inputs and their names are read on the background thread (a system call plus each input's resources). */
    private void loadInputs() {
        background.execute(() -> {
            List<InputItem> found = new ArrayList<>();
            for (TvInputInfo info : TvInputs.list(this)) found.add(new InputItem(info, TvInputs.label(this, info)));
            handler.post(() -> {
                if (sameInputs(found, inputs)) return;
                inputs = found;
                showInputButtons();
            });
        });
    }

    private static boolean sameInputs(List<InputItem> a, List<InputItem> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).info.getId().equals(b.get(i).info.getId())
                    || !TextUtils.equals(a.get(i).label, b.get(i).label)) return false;
        }
        return true;
    }

    private void showInputButtons() {
        boolean barInputs = Prefs.show(prefs, Prefs.SHOW_QUICK_BAR) && Prefs.show(prefs, Prefs.SHOW_BAR_INPUTS);
        List<InputItem> visible = visibleInputs();
        fillInputButtons(barInputs ? visible : new ArrayList<>());
        // The top-bar inputs pop-up would only repeat the quick bar's input buttons, so it shows only without them.
        inputsButton.setVisibility(Prefs.show(prefs, Prefs.SHOW_INPUTS) && !visible.isEmpty() && !barInputs
                ? View.VISIBLE : View.GONE);
    }

    private void fillInputButtons(List<InputItem> items) {
        // Keep focus on the same button (e.g. after switching back from HDMI 1).
        int focused = inputsRow.indexOfChild(getCurrentFocus());
        inputsRow.removeAllViews();
        for (InputItem item : items) inputsRow.addView(inputButton(item));
        inputsRow.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        inputsDivider.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        if (focused >= 0 && focused < inputsRow.getChildCount()) inputsRow.getChildAt(focused).requestFocus();
    }

    /**
     * Loads the app list (names included) on the background thread. Unless forced, the slow part (names and
     * icons) only runs when something changed since last time, including while the launcher wasn't showing:
     * a different set of launchable apps, or an app Android reports as updated (it may have a new icon).
     */
    private void loadApps(boolean force) {
        background.execute(() -> {
            Set<String> found = AppRepo.components(this);
            ChangedPackages changed = pm.getChangedPackages(packageSeq);
            if (!force && appsLoaded && changed == null && found.equals(loadedComponents)) return; // nothing changed
            List<AppRepo.App> apps = AppRepo.load(this);
            loadedComponents = found;
            handler.post(() -> {
                if (changed != null) {
                    packageSeq = changed.getSequenceNumber();
                    for (String pkg : changed.getPackageNames()) images.forget(pkg); // may have a new icon
                }
                allApps.clear();
                allApps.addAll(apps);
                Set<String> installed = new HashSet<>();
                for (AppRepo.App a : apps) installed.add(a.pkg);
                images.retainOnly(installed); // let go of removed apps' images
                refreshLists();
                if (!appsLoaded) {
                    appsLoaded = true;
                    // First load: the grid had nothing to focus until now.
                    if (getCurrentFocus() == null || getCurrentFocus() == grid) {
                        grid.requestFocus();
                        grid.setSelection(0);
                    }
                }
            });
        });
    }

    private void refreshLists() {
        gridApps.clear();
        for (AppRepo.App a : allApps) {
            if (category == AppRepo.CAT_ALL || AppRepo.category(prefs, a) == category) gridApps.add(a);
        }
        // This tab's own arrangement first, then any apps not placed yet (new installs) in A–Z order.
        List<String> order = Prefs.list(prefs, Prefs.ORDER_PREFIX + category);
        gridApps.sort((a, b) -> Integer.compare(rank(order, a.pkg), rank(order, b.pkg))); // stable: A–Z stays A–Z
        adapter.notifyDataSetChanged();
        heading.setText(HEADINGS[category]);
        subheading.setText(moveMode != MOVE_NONE ? MOVE_HINT : SUBHEADINGS[category]);
        subheading.setVisibility(moveMode != MOVE_NONE || cardSize == 0 ? View.VISIBLE : View.GONE);

        List<AppRepo.App> pins = new ArrayList<>();
        for (String pkg : pinned()) {
            AppRepo.App a = find(pkg);
            if (a != null) pins.add(a); // skips uninstalled apps
        }
        while (pinnedRow.getChildCount() > pins.size()) pinnedRow.removeViewAt(pinnedRow.getChildCount() - 1);
        for (int i = 0; i < pins.size(); i++) {
            Tile t;
            if (i < pinnedRow.getChildCount()) {
                t = (Tile) pinnedRow.getChildAt(i);
            } else {
                t = new Tile(this, true, 12);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(BAR_ITEM_DP), dp(BAR_ITEM_DP));
                lp.rightMargin = dp(10);
                pinnedRow.addView(t, lp);
            }
            AppRepo.App app = pins.get(i);
            t.bindIcon(app, images.get(app), theme);
            t.setMoving(moveMode == MOVE_PINS && app.pkg.equals(movingKey), theme);
            t.setOnClickListener(v -> launch(app));
            t.setOnLongClickListener(v -> {
                showPinnedMenu(app);
                return true;
            });
        }
    }

    private static int rank(List<String> order, String key) {
        int i = order.indexOf(key);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    // ---- Moving cards, pinned apps and input buttons ----------------------------------------------------------

    /**
     * Picks up an item (a grid card, a pinned app or an input button) to move with the arrows: OK places it,
     * Back puts everything back. Every step is saved straight away; Back restores the order saved beforehand.
     */
    private void startMove(int mode, String key) {
        moveMode = mode;
        movingKey = key;
        moveOrderKey = mode == MOVE_GRID ? Prefs.ORDER_PREFIX + category
                : mode == MOVE_PINS ? Prefs.FAVS : Prefs.INPUTS_ORDER;
        moveSaved = prefs.contains(moveOrderKey) ? prefs.getString(moveOrderKey, "") : null;
        if (mode == MOVE_GRID) saveOrder(currentKeys()); // pin down the tab's current (maybe A–Z) order
        refreshMoving();
    }

    private void endMove(boolean keep) {
        if (!keep) {
            SharedPreferences.Editor e = prefs.edit();
            if (moveSaved == null) e.remove(moveOrderKey);
            else e.putString(moveOrderKey, moveSaved);
            e.commit();
        }
        int mode = moveMode;
        String key = movingKey;
        moveMode = MOVE_NONE;
        movingKey = null;
        refreshMoving();
        // Keep focus on the item that was moved.
        int i = currentKeys(mode).indexOf(key);
        focusMoved(mode, i);
    }

    /** Arrow keys while moving. Grid cards go in all four directions; quick-bar items only sideways. */
    private void step(int keyCode) {
        List<String> keys = currentKeys();
        int from = keys.indexOf(movingKey), n = keys.size(), cols = Prefs.CARD_COLUMNS[cardSize];
        if (from < 0) return;
        int to = from;
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) to = from - 1;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) to = from + 1;
        else if (moveMode == MOVE_GRID && keyCode == KeyEvent.KEYCODE_DPAD_UP) to = from - cols;
        else if (moveMode == MOVE_GRID && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            // Into the next row; if it's shorter, to its end.
            to = from + cols < n ? from + cols : (from / cols < (n - 1) / cols ? n - 1 : from);
        }
        if (to < 0 || to >= n || to == from) return;
        keys.add(to, keys.remove(from));
        saveOrder(keys);
        refreshMoving();
        focusMoved(moveMode, to);
    }

    /** Keys (packages or input ids) of the moving group, in their shown order. */
    private List<String> currentKeys() {
        return currentKeys(moveMode);
    }

    private List<String> currentKeys(int mode) {
        List<String> keys = new ArrayList<>();
        if (mode == MOVE_GRID) for (AppRepo.App a : gridApps) keys.add(a.pkg);
        else if (mode == MOVE_PINS) keys.addAll(pinned());
        else if (mode == MOVE_INPUTS) for (InputItem i : visibleInputs()) keys.add(i.info.getId());
        return keys;
    }

    private void saveOrder(List<String> keys) {
        if (moveMode == MOVE_INPUTS) {
            // Hidden inputs keep their place after the visible ones.
            List<String> all = new ArrayList<>(keys);
            for (InputItem i : inputs) if (!all.contains(i.info.getId())) all.add(i.info.getId());
            keys = all;
        }
        Prefs.putList(prefs, moveOrderKey, keys);
    }

    private void refreshMoving() {
        refreshLists();
        showInputButtons();
    }

    private void focusMoved(int mode, int index) {
        if (index < 0) return;
        if (mode == MOVE_GRID) {
            grid.requestFocus();
            grid.setSelection(index);
        } else {
            ViewGroup row = mode == MOVE_PINS ? pinnedRow : inputsRow;
            if (index < row.getChildCount()) row.getChildAt(index).requestFocus();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (moveMode == MOVE_NONE) return super.dispatchKeyEvent(event);
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) step(code);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (down && event.getRepeatCount() == 0) endMove(true);
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down && event.getRepeatCount() == 0) endMove(false);
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    private void selectCategory(int cat) {
        if (cat == category) return;
        category = cat;
        markTabs();
        refreshLists();
        grid.setSelection(0);
    }

    private void markTabs() {
        for (int i = 0; i < tabs.getChildCount(); i++) tabs.getChildAt(i).setActivated(i == category);
    }

    private AppRepo.App find(String pkg) {
        for (AppRepo.App a : allApps) {
            if (a.pkg.equals(pkg)) return a;
        }
        return null;
    }

    /** Packages pinned to the quick bar, in order. */
    private List<String> pinned() {
        List<String> out = new ArrayList<>();
        for (String s : prefs.getString(Prefs.FAVS, "").split(",")) {
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private void savePinned(List<String> pins, String focusPkg) {
        prefs.edit().putString(Prefs.FAVS, TextUtils.join(",", pins)).commit();
        refreshLists();
        int i = pins.indexOf(focusPkg);
        if (i >= 0 && i < pinnedRow.getChildCount()) pinnedRow.getChildAt(i).requestFocus();
    }

    private void pickAppToPin() {
        List<String> pins = pinned();
        List<AppRepo.App> choices = new ArrayList<>();
        for (AppRepo.App a : allApps) {
            if (!pins.contains(a.pkg)) choices.add(a);
        }
        if (choices.isEmpty()) {
            Ui.toast(this, "Every app is already in the quick bar");
            return;
        }
        CharSequence[] names = new CharSequence[choices.size()];
        for (int i = 0; i < names.length; i++) names[i] = choices.get(i).label;
        Ui.dialog(this, "Add to quick bar", names, which -> {
            String pkg = choices.get(which).pkg;
            pins.add(pkg);
            savePinned(pins, pkg);
        });
    }

    private void showNetwork(NetworkCapabilities caps) {
        int icon = R.drawable.ic_net_off;
        if (caps != null) {
            icon = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? R.drawable.ic_ethernet : R.drawable.ic_wifi;
        }
        networkIcon.setImageResource(icon);
    }

    // ---- Actions ----------------------------------------------------------------------------------------------

    private void launch(AppRepo.App app) {
        start(AppRepo.launchIntent(app));
    }

    private void start(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Ui.toast(this, "Couldn't open that");
        }
    }

    private void openLauncherSettings() {
        start(new Intent(this, SettingsActivity.class));
    }

    /** The TV's own search (Google Assistant on most Android TVs). */
    private void openSearch() {
        Intent[] options = {
                new Intent(SearchManager.INTENT_ACTION_GLOBAL_SEARCH),
                new Intent(Intent.ACTION_ASSIST),
                new Intent(Intent.ACTION_WEB_SEARCH),
        };
        for (Intent intent : options) {
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                // try the next one
            }
        }
        Ui.toast(this, "This TV has no search app");
    }

    /** Hold OK on a grid card. */
    private void showAppMenu(AppRepo.App app) {
        List<String> pins = pinned();
        List<CharSequence> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (gridApps.size() > 1) {
            items.add("Move");
            actions.add(() -> startMove(MOVE_GRID, app.pkg));
        }
        if (!pins.contains(app.pkg)) {
            items.add("Add to quick bar");
            actions.add(() -> {
                pins.add(app.pkg);
                savePinned(pins, null);
            });
        } else {
            items.add("Remove from quick bar");
            actions.add(() -> {
                pins.remove(app.pkg);
                savePinned(pins, null);
            });
        }
        if (prefs.contains(Prefs.ORDER_PREFIX + category)) {
            items.add("Sort " + AppRepo.CATEGORIES[category] + " A–Z");
            actions.add(() -> {
                prefs.edit().remove(Prefs.ORDER_PREFIX + category).commit();
                refreshLists();
            });
        }
        items.add("Category: " + AppRepo.CATEGORIES[AppRepo.category(prefs, app)]);
        actions.add(() -> pickCategory(app));
        items.add("App info");
        actions.add(() -> start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", app.pkg, null))));
        Ui.dialog(this, app.label, items.toArray(new CharSequence[0]), which -> actions.get(which).run());
    }

    /** Hold OK on a pinned app in the quick bar. */
    private void showPinnedMenu(AppRepo.App app) {
        List<String> pins = pinned();
        List<CharSequence> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (pins.size() > 1) {
            items.add("Move");
            actions.add(() -> startMove(MOVE_PINS, app.pkg));
        }
        items.add("Remove from quick bar");
        actions.add(() -> {
            pins.remove(app.pkg);
            savePinned(pins, null);
        });
        items.add("App info");
        actions.add(() -> start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", app.pkg, null))));
        Ui.dialog(this, app.label, items.toArray(new CharSequence[0]), which -> actions.get(which).run());
    }

    /** Hold OK on an input button in the quick bar. */
    private void showInputMenu(InputItem input) {
        String id = input.info.getId();
        boolean renamed = prefs.contains(Prefs.INPUT_NAME_PREFIX + id);
        List<CharSequence> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (visibleInputs().size() > 1) {
            items.add("Move");
            actions.add(() -> startMove(MOVE_INPUTS, id));
        }
        items.add("Rename…");
        actions.add(() -> renameInput(input));
        if (renamed) {
            items.add("Use the TV's name (" + input.label + ")");
            actions.add(() -> {
                prefs.edit().remove(Prefs.INPUT_NAME_PREFIX + id).commit();
                showInputButtons();
            });
        }
        items.add("Hide from quick bar");
        actions.add(() -> {
            List<String> hidden = Prefs.list(prefs, Prefs.INPUTS_HIDDEN);
            if (!hidden.contains(id)) hidden.add(id);
            Prefs.putList(prefs, Prefs.INPUTS_HIDDEN, hidden);
            showInputButtons();
            Ui.toast(this, "Hidden. Show it again in Launcher settings → TV inputs");
        });
        Ui.dialog(this, displayName(input), items.toArray(new CharSequence[0]), which -> actions.get(which).run());
    }

    private void renameInput(InputItem input) {
        String id = input.info.getId();
        EditText field = new EditText(this);
        field.setSingleLine();
        field.setText(displayName(input));
        field.setSelectAllOnFocus(true);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(field);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Rename " + input.label)
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String name = field.getText().toString().trim();
                    SharedPreferences.Editor e = prefs.edit();
                    if (name.isEmpty() || name.contentEquals(input.label)) e.remove(Prefs.INPUT_NAME_PREFIX + id);
                    else e.putString(Prefs.INPUT_NAME_PREFIX + id, name);
                    e.commit();
                    showInputButtons();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void pickCategory(AppRepo.App app) {
        CharSequence[] items = new CharSequence[AppRepo.CATEGORIES.length];
        items[0] = "Automatic (" + AppRepo.CATEGORIES[app.autoCategory] + ")";
        for (int i = 1; i < items.length; i++) items[i] = AppRepo.CATEGORIES[i];
        Ui.dialog(this, "Category for " + app.label, items, which -> {
            SharedPreferences.Editor e = prefs.edit();
            if (which == 0) e.remove(Prefs.CAT_PREFIX + app.pkg);
            else e.putInt(Prefs.CAT_PREFIX + app.pkg, which);
            e.commit();
            refreshLists();
        });
    }

    private void showInputs() {
        List<InputItem> items = visibleInputs();
        CharSequence[] names = new CharSequence[items.size()];
        for (int i = 0; i < names.length; i++) names[i] = displayName(items.get(i));
        Ui.dialog(this, "TV inputs", names, which -> openInput(items.get(which).info));
    }

    /** The inputs to show, in the user's order (new ones keep the TV's order, at the end), without hidden ones. */
    private List<InputItem> visibleInputs() {
        List<String> order = Prefs.list(prefs, Prefs.INPUTS_ORDER);
        List<String> hidden = Prefs.list(prefs, Prefs.INPUTS_HIDDEN);
        List<InputItem> out = new ArrayList<>();
        for (InputItem i : inputs) if (!hidden.contains(i.info.getId())) out.add(i);
        out.sort((a, b) -> Integer.compare(rank(order, a.info.getId()), rank(order, b.info.getId())));
        return out;
    }

    /** The name the user gave the input, else the TV's. */
    private CharSequence displayName(InputItem input) {
        String name = prefs.getString(Prefs.INPUT_NAME_PREFIX + input.info.getId(), "");
        return name.isEmpty() ? input.label : name;
    }

    /**
     * Asks the TV maker's Live TV app to show the input: the input itself for HDMI/AV, the tuner's channels for
     * ATV/DTV. If the TV doesn't handle that, opens the app that provides the input instead.
     */
    private void openInput(TvInputInfo input) {
        Uri uri = input.isPassthroughInput()
                ? TvContract.buildChannelUriForPassthroughInput(input.getId())
                : TvContract.buildChannelsUriForInput(input.getId());
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        } catch (ActivityNotFoundException | SecurityException e) {
            // fall through to the input's own app
        }
        String pkg = input.getServiceInfo().packageName;
        Intent app = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (app == null) app = pm.getLaunchIntentForPackage(pkg);
        if (app != null) start(app);
        else Ui.toast(this, "This TV doesn't let apps switch inputs");
    }

    /** The system starts the home screen at boot; open the chosen app once per boot. */
    private void openAppAfterBoot() {
        int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        if (boot < 0 || boot == prefs.getInt(Prefs.LAST_BOOT, -1)) return;
        prefs.edit().putInt(Prefs.LAST_BOOT, boot).commit();
        String pkg = prefs.getString(Prefs.AUTOSTART, "");
        if (pkg.isEmpty()) return;
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (intent == null) intent = pm.getLaunchIntentForPackage(pkg);
        if (intent != null) start(intent);
    }

    private int dp(float value) {
        return Ui.dp(this, value);
    }

    /** A TV input and its name (read once, in the background). */
    private static final class InputItem {
        final TvInputInfo info;
        final CharSequence label;

        InputItem(TvInputInfo info, CharSequence label) {
            this.info = info;
            this.label = label;
        }
    }

    /** Grid adapter. GridView recycles tiles; images come from the cache (or arrive shortly after). */
    private final class AppAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return gridApps.size();
        }

        @Override
        public Object getItem(int position) {
            return gridApps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Tile t = convertView instanceof Tile ? (Tile) convertView : new Tile(LauncherActivity.this, false, 16);
            ViewGroup.LayoutParams lp = t.getLayoutParams();
            if (lp == null || lp.height != tileHeight) {
                t.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, tileHeight));
            }
            AppRepo.App app = gridApps.get(position);
            t.bind(app, images.get(app), iconTiles, theme, tileHeight);
            t.setMoving(moveMode == MOVE_GRID && app.pkg.equals(movingKey), theme);
            return t;
        }
    }
}
