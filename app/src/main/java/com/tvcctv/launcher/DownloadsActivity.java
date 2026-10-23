package com.tvcctv.launcher;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Downloader screen: Home (type a URL or a number code), Browser and Files. Runs in its own ":downloads"
 * process so the web engine never loads into the home screen, and that process ends when this screen closes.
 * Android's download service does the downloading, so downloads carry on after leaving.
 */
public final class DownloadsActivity extends Activity {
    /** Number codes resolve through AFTVnews' public short-link service, like their Downloader app. */
    private static final String CODE_URL = "https://aftv.news/";
    private static final String APK_MIME = "application/vnd.android.package-archive";
    private static final int HOME = 0, BROWSER = 1, FILES = 2;
    private static final String[] PANELS = {"Home", "Browser", "Files"};

    private DownloadManager downloads;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Item> items = new ArrayList<>();
    private final FilesAdapter filesAdapter = new FilesAdapter();
    private int panel = HOME;

    private LinearLayout menu, browserPanel, browserBar;
    private View homePanel, filesPanel;
    private EditText urlBox;
    private TextView address, filesEmpty;
    private ListView filesList;
    private CursorWebView browser; // created the first time the Browser is used

    /** One row of the Files list, read from Android's download service. */
    private static final class Item {
        long id;
        String name, mime;
        int status, reason;
        long done, total;

        boolean isApk() {
            return APK_MIME.equals(mime) || (name != null && name.toLowerCase(Locale.ROOT).endsWith(".apk"));
        }

        boolean inProgress() {
            return status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING
                    || status == DownloadManager.STATUS_PAUSED;
        }
    }

    // When an APK finishes while this screen is open, go straight to Android's install screen.
    private final BroadcastReceiver completeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            refreshFiles();
            for (Item it : items) {
                if (it.id == id && it.status == DownloadManager.STATUS_SUCCESSFUL && it.isApk()) install(it);
            }
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refreshFiles();
            for (Item it : items) {
                if (it.inProgress()) {
                    handler.postDelayed(this, 1000);
                    return;
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        downloads = getSystemService(DownloadManager.class);
        setContentView(buildUi());
        // Sent by the download service, which is another app, hence exported.
        registerReceiver(completeReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Build.VERSION.SDK_INT >= 33 ? Context.RECEIVER_EXPORTED : 0);
        show(HOME);
        urlBox.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (panel == FILES) startTicker();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(completeReceiver);
        handler.removeCallbacksAndMessages(null);
        if (browser != null) browser.web.destroy();
        super.onDestroy();
        // Give all of this process's memory back (the web engine is large). Downloads continue in the system.
        if (isFinishing()) Process.killProcess(Process.myPid());
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (panel == BROWSER && browser != null && browser.web.hasFocus() && browser.web.canGoBack()) {
                browser.web.goBack();
                return true;
            }
            if (panel != HOME) {
                show(HOME);
                menu.getChildAt(1 + HOME).requestFocus();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---- Layout -----------------------------------------------------------------------------------------------

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(0xFF15171A);
        root.setPadding(dp(48), dp(27), dp(48), dp(27));

        menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.text(this, 26, Color.WHITE);
        title.setText("Downloads");
        title.setPadding(dp(12), 0, 0, dp(20));
        menu.addView(title);
        for (int i = 0; i < PANELS.length; i++) menu.addView(menuItem(i));
        root.addView(menu, new LinearLayout.LayoutParams(dp(200), ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout content = new FrameLayout(this);
        homePanel = buildHome();
        browserPanel = new LinearLayout(this);
        browserPanel.setOrientation(LinearLayout.VERTICAL);
        filesPanel = buildFiles();
        content.addView(homePanel);
        content.addView(browserPanel);
        content.addView(filesPanel);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lp.leftMargin = dp(24);
        root.addView(content, lp);
        return root;
    }

    private TextView menuItem(int index) {
        TextView t = Ui.text(this, 18, Color.WHITE);
        t.setText(PANELS[index]);
        t.setPadding(dp(16), dp(12), dp(16), dp(12));
        t.setFocusable(true);
        GradientDrawable current = new GradientDrawable();
        current.setCornerRadius(dp(8));
        current.setColor(0x26FFFFFF);
        StateListDrawable bg = Ui.focusBackground(this, false);
        bg.addState(new int[]{android.R.attr.state_activated}, current);
        t.setBackground(bg);
        t.setOnClickListener(v -> show(index));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    private View buildHome() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        TextView heading = Ui.text(this, 22, Color.WHITE);
        heading.setText("Enter a web address or code");
        v.addView(heading);
        TextView hint = Ui.text(this, 15, 0xB3FFFFFF);
        hint.setText("A link to a file (such as an APK) downloads straight away; a web page opens in the browser. "
                + "A number like 12345 opens aftv.news/12345.");
        hint.setPadding(0, dp(8), 0, dp(20));
        v.addView(hint);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        urlBox = new EditText(this);
        urlBox.setSingleLine();
        urlBox.setTextSize(22);
        urlBox.setTextColor(Color.WHITE);
        urlBox.setHintTextColor(0x66FFFFFF);
        urlBox.setHint("https://");
        urlBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlBox.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlBox.setOnEditorActionListener((tv, action, event) -> {
            go();
            return true;
        });
        row.addView(urlBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView goButton = button("Go", v2 -> go());
        ((LinearLayout.LayoutParams) goButton.getLayoutParams()).leftMargin = dp(12);
        row.addView(goButton);
        v.addView(row);
        return v;
    }

    private View buildFiles() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        TextView heading = Ui.text(this, 22, Color.WHITE);
        heading.setText("Files");
        v.addView(heading);
        TextView hint = Ui.text(this, 15, 0xB3FFFFFF);
        hint.setText("Press OK on a file to install, open or delete it. Files are saved in the Download folder.");
        hint.setPadding(0, dp(8), 0, dp(12));
        v.addView(hint);
        filesEmpty = Ui.text(this, 17, 0x99FFFFFF);
        filesEmpty.setText("Nothing downloaded yet.");
        v.addView(filesEmpty);
        filesList = new ListView(this);
        filesList.setAdapter(filesAdapter);
        filesList.setOnItemClickListener((parent, view, pos, id) -> fileMenu(items.get(pos)));
        v.addView(filesList, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return v;
    }

    /** The browser is built on first use, so Home and Files never load the web engine. */
    private void ensureBrowser() {
        if (browser != null) return;
        browserBar = new LinearLayout(this);
        browserBar.setGravity(Gravity.CENTER_VERTICAL);
        browserBar.addView(button("Back", v -> {
            if (browser.web.canGoBack()) browser.web.goBack();
        }));
        browserBar.addView(button("Forward", v -> {
            if (browser.web.canGoForward()) browser.web.goForward();
        }));
        browserBar.addView(button("Reload", v -> browser.web.reload()));
        address = Ui.text(this, 15, 0xB3FFFFFF);
        address.setSingleLine();
        address.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        address.setFocusable(true);
        address.setPadding(dp(12), dp(10), dp(12), dp(10));
        address.setBackground(Ui.focusBackground(this, false));
        address.setOnClickListener(v -> {
            // Edit the address on the Home tab.
            urlBox.setText(browser.web.getUrl());
            show(HOME);
            urlBox.requestFocus();
        });
        browserBar.addView(address, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        browserPanel.addView(browserBar);

        browser = new CursorWebView(this);
        browser.onExitLeft = () -> menu.getChildAt(1 + BROWSER).requestFocus();
        browser.onExitTop = () -> browserBar.getChildAt(0).requestFocus();
        WebSettings s = browser.web.getSettings();
        s.setJavaScriptEnabled(true); // most download sites need it
        s.setDomStorageEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        browser.web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) return false;
                // market://, intent:// and similar: hand them to whichever app handles them.
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl()));
                } catch (ActivityNotFoundException | SecurityException e) {
                    Ui.toast(DownloadsActivity.this, "No app can open that link");
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                address.setText(url);
            }
        });
        browser.web.setDownloadListener((url, userAgent, disposition, mime, length) ->
                download(url, userAgent, disposition, mime, true));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = dp(8);
        browserPanel.addView(browser, lp);
    }

    private TextView button(String text, View.OnClickListener onClick) {
        TextView b = Ui.text(this, 16, Color.WHITE);
        b.setText(text);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(18), dp(10), dp(18), dp(10));
        b.setFocusable(true);
        GradientDrawable rest = new GradientDrawable();
        rest.setCornerRadius(dp(8));
        rest.setColor(0x26FFFFFF);
        StateListDrawable bg = Ui.focusBackground(this, false);
        bg.addState(new int[0], rest);
        b.setBackground(bg);
        b.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private void show(int which) {
        panel = which;
        if (which == BROWSER) ensureBrowser();
        homePanel.setVisibility(which == HOME ? View.VISIBLE : View.GONE);
        browserPanel.setVisibility(which == BROWSER ? View.VISIBLE : View.GONE);
        filesPanel.setVisibility(which == FILES ? View.VISIBLE : View.GONE);
        for (int i = 0; i < PANELS.length; i++) menu.getChildAt(1 + i).setActivated(i == which);
        handler.removeCallbacks(ticker);
        if (which == FILES) startTicker();
    }

    // ---- Home -------------------------------------------------------------------------------------------------

    private void go() {
        String input = urlBox.getText().toString().trim();
        if (input.isEmpty()) return;
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(urlBox.getWindowToken(), 0);
        String url;
        if (input.matches("\\d{1,10}")) url = CODE_URL + input;
        else if (input.contains("://")) url = input;
        else url = "https://" + input;

        if (looksLikeApk(url)) {
            download(url, null, null, APK_MIME, false);
        } else {
            show(BROWSER);
            browser.web.loadUrl(url);
            browser.web.requestFocus();
        }
    }

    private static boolean looksLikeApk(String url) {
        String path = Uri.parse(url).getPath();
        return path != null && path.toLowerCase(Locale.ROOT).endsWith(".apk");
    }

    // ---- Downloads --------------------------------------------------------------------------------------------

    private void download(String url, String userAgent, String disposition, String mime, boolean fromBrowser) {
        boolean apk = APK_MIME.equals(mime) || looksLikeApk(url)
                || (disposition != null && disposition.toLowerCase(Locale.ROOT).contains(".apk"));
        // Servers often label APKs as generic binaries; keep the .apk name so it can be installed.
        String name = URLUtil.guessFileName(url, disposition, apk ? APK_MIME : mime);
        if (apk && !name.toLowerCase(Locale.ROOT).endsWith(".apk")) name = name.replaceFirst("\\.[^.]*$", "") + ".apk";

        DownloadManager.Request r;
        try {
            r = new DownloadManager.Request(Uri.parse(url));
        } catch (IllegalArgumentException e) {
            Ui.toast(this, "Only http and https links can be downloaded");
            return;
        }
        r.setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
        if (apk) r.setMimeType(APK_MIME);
        else if (!TextUtils.isEmpty(mime)) r.setMimeType(mime);
        if (userAgent != null) r.addRequestHeader("User-Agent", userAgent);
        if (fromBrowser) {
            // Some sites only serve the file to the same signed-in browser session.
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) r.addRequestHeader("Cookie", cookies);
        }
        try {
            downloads.enqueue(r);
        } catch (IllegalArgumentException | SecurityException | IllegalStateException e) {
            Ui.toast(this, "Couldn't start that download");
            return;
        }
        Ui.toast(this, "Downloading " + name);
        show(FILES);
        menu.getChildAt(1 + FILES).requestFocus();
    }

    private void startTicker() {
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    /** Lists this app's downloads (Android only returns our own), newest first. */
    private void refreshFiles() {
        items.clear();
        try (Cursor c = downloads.query(new DownloadManager.Query())) {
            if (c != null) {
                int id = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID);
                int title = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE);
                int status = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS);
                int done = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                int total = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                int mime = c.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE);
                int reason = c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON);
                while (c.moveToNext()) {
                    Item it = new Item();
                    it.id = c.getLong(id);
                    it.name = c.getString(title);
                    it.status = c.getInt(status);
                    it.done = c.getLong(done);
                    it.total = c.getLong(total);
                    it.mime = c.getString(mime);
                    it.reason = c.getInt(reason);
                    items.add(0, it);
                }
            }
        }
        filesAdapter.notifyDataSetChanged();
        filesEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void fileMenu(Item it) {
        List<CharSequence> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (it.status == DownloadManager.STATUS_SUCCESSFUL) {
            if (it.isApk()) {
                labels.add("Install");
                actions.add(() -> install(it));
            } else {
                labels.add("Open");
                actions.add(() -> open(it));
            }
        }
        labels.add(it.inProgress() ? "Cancel download" : "Delete");
        actions.add(() -> {
            downloads.remove(it.id); // also deletes the file
            refreshFiles();
        });
        Ui.dialog(this, it.name, labels.toArray(new CharSequence[0]), which -> actions.get(which).run());
    }

    /**
     * Opens Android's own installer, which always asks for confirmation. Installing needs the "Install unknown
     * apps" special access for this launcher; without it we open that settings page first, since some TV builds
     * don't lead you there from the installer.
     */
    private void install(Item it) {
        Uri uri = downloads.getUriForDownloadedFile(it.id);
        if (uri == null) {
            Ui.toast(this, "That file is no longer there");
            return;
        }
        if (!getPackageManager().canRequestPackageInstalls()) {
            Ui.toast(this, "Turn on “Allow from this source”, then press Back and choose Install again");
            Intent allow = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.fromParts("package", getPackageName(), null));
            try {
                startActivity(allow);
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                // No such settings page on this TV: let the installer ask instead.
            }
        }
        start(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME));
    }

    private void open(Item it) {
        Uri uri = downloads.getUriForDownloadedFile(it.id);
        if (uri == null) {
            Ui.toast(this, "That file is no longer there");
            return;
        }
        start(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, it.mime));
    }

    private void start(Intent intent) {
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Ui.toast(this, "No app on this TV can open that file");
        }
    }

    private CharSequence describe(Item it) {
        String size = it.total > 0 ? Formatter.formatShortFileSize(this, it.total) : "";
        String status;
        switch (it.status) {
            case DownloadManager.STATUS_RUNNING:
                status = it.total > 0
                        ? "Downloading " + (it.done * 100 / it.total) + "% · "
                                + Formatter.formatShortFileSize(this, it.done) + " of " + size
                        : "Downloading " + Formatter.formatShortFileSize(this, it.done);
                break;
            case DownloadManager.STATUS_PENDING:
                status = "Waiting to start…";
                break;
            case DownloadManager.STATUS_PAUSED:
                switch (it.reason) {
                    case DownloadManager.PAUSED_WAITING_FOR_NETWORK:
                        status = "Paused, waiting for network";
                        break;
                    case DownloadManager.PAUSED_QUEUED_FOR_WIFI:
                        status = "Paused, waiting for Wi-Fi";
                        break;
                    case DownloadManager.PAUSED_WAITING_TO_RETRY:
                        status = "Connection problem, retrying shortly…";
                        break;
                    default:
                        status = "Paused";
                        break;
                }
                break;
            case DownloadManager.STATUS_SUCCESSFUL:
                status = "Done · " + size + (it.isApk() ? " · press OK to install" : "");
                break;
            default:
                status = "Failed";
                break;
        }
        SpannableStringBuilder s = new SpannableStringBuilder(it.name).append('\n');
        int start = s.length();
        s.append(status);
        s.setSpan(new RelativeSizeSpan(0.8f), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new ForegroundColorSpan(0xB3FFFFFF), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    private int dp(float value) {
        return Ui.dp(this, value);
    }

    private final class FilesAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return items.get(position).id;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView t = convertView instanceof TextView ? (TextView) convertView : Ui.text(DownloadsActivity.this, 18, Color.WHITE);
            t.setPadding(dp(16), dp(10), dp(16), dp(10));
            t.setText(describe(items.get(position)));
            return t;
        }
    }
}
