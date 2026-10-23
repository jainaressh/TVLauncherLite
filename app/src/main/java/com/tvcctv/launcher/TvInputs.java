package com.tvcctv.launcher;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

/** The TV's own sources, shared by the home screen and the settings screen. */
final class TvInputs {
    private TvInputs() {}

    /**
     * Sources of a TV with built-in Android, in the TV's own order: HDMI/AV/component inputs plus the TV's
     * ATV/DTV tuners. Empty on boxes, sticks and emulators.
     */
    static List<TvInputInfo> list(Context c) {
        List<TvInputInfo> out = new ArrayList<>();
        TvInputManager tim = c.getSystemService(TvInputManager.class);
        if (tim == null) return out;
        for (TvInputInfo info : tim.getTvInputList()) {
            if (!info.isHidden(c) && (info.isPassthroughInput() || isBuiltInTuner(info))) out.add(info);
        }
        return out;
    }

    /** Streaming apps can register tuner-type inputs too; only the TV maker's (system) ones are real tuners. */
    private static boolean isBuiltInTuner(TvInputInfo info) {
        return info.getType() == TvInputInfo.TYPE_TUNER
                && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    /** The TV's name for the input. */
    static CharSequence label(Context c, TvInputInfo input) {
        // The custom label holds the HDMI-CEC device name (e.g. "PlayStation") when there is one.
        CharSequence label = input.loadCustomLabel(c);
        if (TextUtils.isEmpty(label)) label = input.loadLabel(c);
        return TextUtils.isEmpty(label) ? "TV" : label;
    }
}
