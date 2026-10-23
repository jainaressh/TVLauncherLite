package com.tvcctv.launcher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebView;
import android.widget.FrameLayout;

/**
 * A WebView driven by a D-pad mouse pointer: TV remotes can't tap links, so the arrows move a pointer and OK
 * taps where it is. Pushing past the page edge scrolls the page; past an edge that can't scroll, focus leaves.
 */
final class CursorWebView extends FrameLayout {
    final WebView web;
    private final View cursor;
    private final int radius;
    private float cx = -1, cy = -1;

    /** Called when the pointer is pushed off the left edge or the top edge of a page that can't scroll further. */
    Runnable onExitLeft, onExitTop;

    CursorWebView(Context c) {
        super(c);
        web = new WebView(c);
        addView(web, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        radius = Ui.dp(c, 11);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        // Blue with a white ring: visible on light and dark pages alike.
        dot.setColor(0xFF1A73E8);
        dot.setStroke(Ui.dp(c, 3), Color.WHITE);
        cursor = new View(c);
        cursor.setBackground(dot);
        cursor.setFocusable(false);
        addView(cursor, new LayoutParams(radius * 2, radius * 2));
        web.setOnFocusChangeListener((v, hasFocus) -> cursor.setAlpha(hasFocus ? 1f : 0.35f));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        if (cx < 0) placeCursor(w / 2f, h / 3f);
        else placeCursor(Math.min(cx, w - 1), Math.min(cy, h - 1));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (!web.hasFocus()) return super.dispatchKeyEvent(e);
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) move(e.getKeyCode(), e.getRepeatCount());
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (down && e.getRepeatCount() == 0) tap();
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private void move(int key, int repeat) {
        // Speeds up while the key is held.
        int step = Ui.dp(getContext(), 6) * (2 + Math.min(repeat, 12)) / 2;
        float x = cx, y = cy;
        int w = getWidth(), h = getHeight();
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (x <= 0) {
                    if (web.canScrollHorizontally(-1)) web.scrollBy(-step * 2, 0);
                    else if (onExitLeft != null) onExitLeft.run();
                }
                x -= step;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (x >= w - 1) web.scrollBy(step * 2, 0);
                x += step;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (y <= 0) {
                    if (web.canScrollVertically(-1)) web.scrollBy(0, -step * 2);
                    else if (onExitTop != null) onExitTop.run();
                }
                y -= step;
                break;
            default:
                if (y >= h - 1) web.scrollBy(0, step * 2);
                y += step;
                break;
        }
        placeCursor(Math.max(0, Math.min(x, w - 1)), Math.max(0, Math.min(y, h - 1)));
    }

    private void placeCursor(float x, float y) {
        cx = x;
        cy = y;
        cursor.setTranslationX(x - radius);
        cursor.setTranslationY(y - radius);
    }

    /** A real touch tap at the pointer, so links, buttons and text fields behave as on a phone. */
    private void tap() {
        long t = SystemClock.uptimeMillis();
        MotionEvent downEvent = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, cx, cy, 0);
        MotionEvent upEvent = MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_UP, cx, cy, 0);
        downEvent.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        upEvent.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        web.dispatchTouchEvent(downEvent);
        web.dispatchTouchEvent(upEvent);
        downEvent.recycle();
        upEvent.recycle();
    }
}
