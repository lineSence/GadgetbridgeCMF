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
package nodomain.freeyourgadget.gadgetbridge.service.devices.cmfwatchpro.watchface;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Stores the clock overlay settings picked in the watchface editor.
 *
 * <p>The watchface file itself carries only pixels. Style, position and colour are sent separately
 * in the transfer descriptor, and the transfer runs in the device service rather than in the
 * editor. These preferences are the handover point between the two: the editor writes them, and
 * {@code CmfDataUploader} reads them when it builds the descriptor.</p>
 */
public class CmfWatchfacePrefs {
    public static final String PREFS_NAME = "cmf_watchface";

    private static final String PREF_STYLE = "style_id";
    private static final String PREF_POSITION_X = "position_x";
    private static final String PREF_POSITION_Y = "position_y";
    private static final String PREF_COLOR = "color_argb";
    private static final String PREF_LAST_FILE = "last_file";

    private final SharedPreferences prefs;

    public CmfWatchfacePrefs(final Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Reads the stored overlay settings, falling back to the defaults. */
    public CmfPhotoWatchface.Params getParams() {
        final CmfPhotoWatchface.Params params = new CmfPhotoWatchface.Params();
        params.styleId = prefs.getInt(PREF_STYLE, params.styleId);
        params.positionX = prefs.getInt(PREF_POSITION_X, params.positionX);
        params.positionY = prefs.getInt(PREF_POSITION_Y, params.positionY);
        params.colorArgb = prefs.getInt(PREF_COLOR, params.colorArgb);
        return params;
    }

    public void setParams(final CmfPhotoWatchface.Params params) {
        prefs.edit()
                .putInt(PREF_STYLE, params.styleId)
                .putInt(PREF_POSITION_X, params.positionX)
                .putInt(PREF_POSITION_Y, params.positionY)
                .putInt(PREF_COLOR, params.colorArgb)
                .apply();
    }

    /** Absolute path of the last built watchface, so the editor can offer it again. */
    public String getLastFile() {
        return prefs.getString(PREF_LAST_FILE, null);
    }

    public void setLastFile(final String path) {
        prefs.edit().putString(PREF_LAST_FILE, path).apply();
    }
}
