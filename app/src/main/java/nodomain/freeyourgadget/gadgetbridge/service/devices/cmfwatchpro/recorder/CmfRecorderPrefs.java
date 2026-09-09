/*  Copyright (C) 2026 GadgetbridgeCMF contributors

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.  */
package nodomain.freeyourgadget.gadgetbridge.service.devices.cmfwatchpro.recorder;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Settings for the CMF voice recorder: how long to record, in which format and where the
 * finished file should be copied.
 *
 * <p>Stored in a dedicated preferences file so the recorder keeps working even when no
 * watch is currently connected, and so nothing in the shared Gadgetbridge preferences has
 * to be touched.</p>
 */
public final class CmfRecorderPrefs {
    public static final String PREFS_NAME = "cmf_recorder";

    public static final String KEY_DURATION_SECONDS = "duration_seconds";
    public static final String KEY_FORMAT = "format";
    public static final String KEY_PHONE_MIC = "phone_mic";
    public static final String KEY_FOLDER_URI = "folder_uri";

    public static final String FORMAT_WAV = "wav";
    public static final String FORMAT_M4A = "m4a";

    public static final int DEFAULT_DURATION_SECONDS = 0;

    /** 0 means "until stopped". */
    public static final int[] DURATION_OPTIONS_SECONDS = {0, 10, 30, 60, 300, 900, 1800, 3600};

    public static final String[] DURATION_OPTION_LABELS = {
            "Без ограничения",
            "10 секунд (самотест)",
            "30 секунд",
            "1 минута",
            "5 минут",
            "15 минут",
            "30 минут",
            "1 час",
    };

    public static final String[] FORMAT_OPTIONS = {FORMAT_WAV, FORMAT_M4A};

    public static final String[] FORMAT_LABELS = {
            "WAV · PCM 16 бит (с метриками качества)",
            "M4A · AAC 64 кбит/с (компактный)",
    };

    private CmfRecorderPrefs() {
        // utility class
    }

    public static SharedPreferences prefs(final Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static int getDurationSeconds(final Context context) {
        return prefs(context).getInt(KEY_DURATION_SECONDS, DEFAULT_DURATION_SECONDS);
    }

    public static void setDurationSeconds(final Context context, final int seconds) {
        prefs(context).edit().putInt(KEY_DURATION_SECONDS, seconds).apply();
    }

    public static String getFormat(final Context context) {
        final String stored = prefs(context).getString(KEY_FORMAT, FORMAT_WAV);
        return FORMAT_M4A.equals(stored) ? FORMAT_M4A : FORMAT_WAV;
    }

    public static void setFormat(final Context context, final String format) {
        prefs(context).edit().putString(KEY_FORMAT, format).apply();
    }

    public static boolean isPhoneMic(final Context context) {
        return prefs(context).getBoolean(KEY_PHONE_MIC, false);
    }

    public static void setPhoneMic(final Context context, final boolean phoneMic) {
        prefs(context).edit().putBoolean(KEY_PHONE_MIC, phoneMic).apply();
    }

    /** Persisted SAF tree uri of the output folder, or null when recordings stay in app storage. */
    public static String getFolderUri(final Context context) {
        final String stored = prefs(context).getString(KEY_FOLDER_URI, null);
        return stored != null && !stored.isEmpty() ? stored : null;
    }

    public static void setFolderUri(final Context context, final String uri) {
        prefs(context).edit().putString(KEY_FOLDER_URI, uri).apply();
    }

    public static String extensionFor(final String format) {
        return FORMAT_M4A.equals(format) ? ".m4a" : ".wav";
    }

    public static String mimeFor(final String format) {
        return FORMAT_M4A.equals(format) ? "audio/mp4" : "audio/x-wav";
    }

    public static int indexOfDuration(final int seconds) {
        for (int i = 0; i < DURATION_OPTIONS_SECONDS.length; i++) {
            if (DURATION_OPTIONS_SECONDS[i] == seconds) {
                return i;
            }
        }
        return 0;
    }

    public static int indexOfFormat(final String format) {
        return FORMAT_M4A.equals(format) ? 1 : 0;
    }

    public static String labelForDuration(final int seconds) {
        return DURATION_OPTION_LABELS[indexOfDuration(seconds)];
    }

    public static String labelForFormat(final String format) {
        return FORMAT_LABELS[indexOfFormat(format)];
    }
}
