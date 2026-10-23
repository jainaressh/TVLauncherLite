package com.tvcctv.launcher;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.view.KeyEvent;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * A simple folder and image list for picking a wallpaper. Many TVs have no system file picker,
 * so this works everywhere: internal storage, SD cards and USB drives.
 */
public final class ImageBrowserActivity extends Activity {
    private static final int REQUEST_PERMISSION = 1;
    private static final String[] IMAGE_TYPES = {".jpg", ".jpeg", ".png", ".webp"};

    private final List<File> roots = new ArrayList<>();
    private final List<File> entries = new ArrayList<>();
    private final List<String> names = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private ListView listView;
    private TextView pathView;
    /** Folder being shown, or null for the list of storage volumes. */
    private File current;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF15171A);
        root.setPadding(Ui.dp(this, 48), Ui.dp(this, 27), Ui.dp(this, 48), 0);
        pathView = Ui.text(this, 20, Color.WHITE);
        pathView.setPadding(0, 0, 0, Ui.dp(this, 12));
        root.addView(pathView);
        listView = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, pos, id) -> open(pos));
        root.addView(listView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        String permission = Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            showRoots();
        } else {
            requestPermissions(new String[]{permission}, REQUEST_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            showRoots();
            return;
        }
        if (permissions.length > 0 && !shouldShowRequestPermissionRationale(permissions[0])) {
            // Refused with "Don't ask again" (or blocked): Android won't ask any more, so open the app's page.
            Ui.toast(this, "Allow “Files and media” (Storage) for TV Launcher Lite, then try again");
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null)));
            } catch (ActivityNotFoundException e) {
                // nothing more we can do
            }
        } else {
            Ui.toast(this, "Storage access is needed to pick an image");
        }
        finish();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && current != null) {
            up();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void showRoots() {
        current = null;
        roots.clear();
        entries.clear();
        names.clear();
        for (StorageVolume v : getSystemService(StorageManager.class).getStorageVolumes()) {
            File dir = v.getDirectory();
            if (dir == null) continue; // not mounted
            roots.add(dir);
            entries.add(dir);
            names.add(v.getDescription(this));
        }
        pathView.setText("Choose storage");
        refresh();
    }

    private void show(File dir) {
        current = dir;
        entries.clear();
        names.clear();
        entries.add(null);
        names.add("↑  Up");
        File[] files = dir.listFiles();
        if (files != null) {
            Arrays.sort(files, (a, b) -> a.isDirectory() != b.isDirectory()
                    ? (a.isDirectory() ? -1 : 1)
                    : a.getName().compareToIgnoreCase(b.getName()));
            for (File f : files) {
                if (f.getName().startsWith(".")) continue;
                if (f.isDirectory()) {
                    entries.add(f);
                    names.add(f.getName() + "/");
                } else if (isImage(f)) {
                    entries.add(f);
                    names.add(f.getName());
                }
            }
        }
        pathView.setText(dir.getPath());
        refresh();
    }

    private void refresh() {
        adapter.notifyDataSetChanged();
        listView.setSelection(0);
        listView.requestFocus();
    }

    private void up() {
        File parent = current.getParentFile();
        if (roots.contains(current) || parent == null) showRoots();
        else show(parent);
    }

    private void open(int position) {
        File f = entries.get(position);
        if (f == null) up();
        else if (f.isDirectory()) show(f);
        else pick(f);
    }

    private void pick(File image) {
        Ui.toast(this, "Setting wallpaper…");
        listView.setEnabled(false);
        // Save the wallpaper at the TV's full resolution, landscape.
        Rect screen = getWindowManager().getMaximumWindowMetrics().getBounds();
        int w = Math.max(screen.width(), screen.height());
        int h = Math.min(screen.width(), screen.height());
        new Thread(() -> {
            boolean ok = Wallpaper.save(this, image, w, h);
            runOnUiThread(() -> {
                if (ok) {
                    SharedPreferences prefs = Prefs.get(this);
                    SharedPreferences.Editor e = prefs.edit().putInt(Prefs.WP_TYPE, Prefs.WP_IMAGE);
                    Prefs.bumpWallpaper(prefs, e);
                    e.commit();
                    finish();
                } else {
                    Ui.toast(this, "Couldn't open that image");
                    listView.setEnabled(true);
                }
            });
        }).start();
    }

    private static boolean isImage(File f) {
        String name = f.getName().toLowerCase(Locale.ROOT);
        for (String type : IMAGE_TYPES) {
            if (name.endsWith(type)) return true;
        }
        return false;
    }
}
