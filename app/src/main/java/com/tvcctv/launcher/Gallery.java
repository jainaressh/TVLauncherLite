package com.tvcctv.launcher;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * A picker shown as a gallery of large preview tiles (wallpapers, colours, gradients) instead of a text list.
 * Previews only exist while it is open, inside the settings screen.
 */
final class Gallery {
    static final class Item {
        final CharSequence label;
        final Drawable preview;

        Item(CharSequence label, Drawable preview) {
            this.label = label;
            this.preview = preview;
        }
    }

    private Gallery() {}

    /** current: index of the item in use now (marked with a tick), or -1. */
    static void show(Context c, String title, List<Item> items, int current, IntConsumer onPick) {
        int n = items.size();
        int columns = n <= 4 ? n : n <= 6 ? 3 : n <= 10 ? 5 : 6;
        int screenW = c.getResources().getDisplayMetrics().widthPixels;
        int width = Math.min(screenW - Ui.dp(c, 96), Ui.dp(c, 900));
        int pad = Ui.dp(c, 28), spacing = Ui.dp(c, 16);
        int tileW = (width - 2 * pad - spacing * (columns - 1)) / columns;
        int previewH = tileW * 9 / 16;

        Dialog dialog = new Dialog(c, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        GradientDrawable panel = new GradientDrawable();
        panel.setCornerRadius(Ui.dp(c, 20));
        panel.setColor(0xFF1E2329);
        root.setBackground(panel);

        TextView heading = Ui.text(c, 22, Color.WHITE);
        heading.setText(title);
        heading.setPadding(0, 0, 0, Ui.dp(c, 18));
        root.addView(heading);

        GridView grid = new GridView(c);
        grid.setNumColumns(columns);
        grid.setHorizontalSpacing(spacing);
        grid.setVerticalSpacing(spacing);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT)); // tiles draw their own focus ring
        grid.setClipToPadding(false);
        int growRoom = Ui.dp(c, 10); // a focused tile grows 6%; keep its ring inside the grid
        grid.setPadding(growRoom, growRoom, growRoom, growRoom);
        grid.setVerticalScrollBarEnabled(false);
        grid.setAdapter(new BaseAdapter() {
            @Override
            public int getCount() {
                return n;
            }

            @Override
            public Object getItem(int position) {
                return items.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                return cell(c, items.get(position), position == current, previewH);
            }
        });
        grid.setOnItemClickListener((parent, view, position, id) -> {
            dialog.dismiss();
            onPick.accept(position);
        });
        grid.setOnFocusChangeListener((v, hasFocus) -> grid.requestLayout());
        root.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dialog.setContentView(root);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        dialog.show();
        grid.setSelection(Math.max(0, current));
        grid.requestFocus();
    }

    private static View cell(Context c, Item item, boolean isCurrent, int previewH) {
        Cell cell = new Cell(c);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setLayoutParams(new AbsListView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        float radius = Ui.dp(c, 12);
        FrameLayout preview = new FrameLayout(c);
        // GridView may build a cell more than once; a drawable can only belong to one view at a time.
        Drawable.ConstantState state = item.preview.getConstantState();
        preview.setBackground(state != null ? state.newDrawable(c.getResources()) : item.preview);
        preview.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        preview.setClipToOutline(true);
        // A faint edge so dark swatches still read against the dark panel; white ring while focused.
        GradientDrawable edge = new GradientDrawable();
        edge.setCornerRadius(radius);
        edge.setStroke(Ui.dp(c, 1), 0x33FFFFFF);
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(radius);
        ring.setStroke(Ui.dp(c, 3), Color.WHITE);
        StateListDrawable fg = new StateListDrawable();
        fg.addState(new int[]{android.R.attr.state_selected}, ring);
        fg.addState(new int[0], edge);
        preview.setForeground(fg);
        preview.setDuplicateParentStateEnabled(true);
        cell.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, previewH));

        TextView label = new TextView(c);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        label.setTextColor(isCurrent ? 0xFF8AB4F8 : 0xE6FFFFFF);
        label.setText(isCurrent ? "✓ " + item.label : item.label);
        label.setSingleLine();
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, Ui.dp(c, 8), 0, 0);
        cell.addView(label);
        return cell;
    }

    /** Grows a little while it is the selected (focused) tile. */
    private static final class Cell extends LinearLayout {
        Cell(Context c) {
            super(c);
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            float scale = isSelected() ? 1.06f : 1f;
            if (getScaleX() != scale) animate().scaleX(scale).scaleY(scale).setDuration(120).start();
        }
    }
}
