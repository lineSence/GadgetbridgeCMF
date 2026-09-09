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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shared state between the watchface screens and the device service.
 *
 * <p>The list of installed watchfaces is read over Bluetooth by the device service, while the user
 * picks the watchface to replace on a normal activity. The two run in different components, so the
 * answer from the watch and the choice made by the user are both kept here, in the same preference
 * file the watchface editor already uses.</p>
 *
 * <p>The watch has a fixed number of dial slots. A new watchface therefore always costs an old one,
 * which is why the upload refuses to start until a replacement target is chosen.</p>
 */
public class CmfWatchfaceSlots {
    /** Asks the device service to read the dial list from the watch. */
    public static final String CONFIG_DIAL_LIST_REFRESH = "cmf_dial_list_refresh";

    /** Asks the device service to make the selected watchface active. */
    public static final String CONFIG_DIAL_ACTIVATE = "cmf_dial_activate";

    /** Asks the device service to delete the selected watchface from the watch. */
    public static final String CONFIG_DIAL_DELETE = "cmf_dial_delete";

    private static final String PREF_IDS = "dial_ids";
    private static final String PREF_ACTIVE_INDEX = "dial_active_index";
    private static final String PREF_TOTAL = "dial_total";
    private static final String PREF_MAX = "dial_max";
    private static final String PREF_RAW = "dial_raw";
    private static final String PREF_UPDATED = "dial_updated";
    private static final String PREF_TARGET = "dial_target";
    private static final String PREF_RESULT = "dial_result";

    private final SharedPreferences prefs;

    public CmfWatchfaceSlots(final Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(CmfWatchfacePrefs.PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Exposed so a screen can redraw itself as soon as an answer from the watch arrives. */
    public SharedPreferences getPreferences() {
        return prefs;
    }

    /** Stores a fresh answer from the watch. */
    public void save(final CmfDialList.Dials dials) {
        final StringBuilder ids = new StringBuilder();
        for (final Integer id : dials.ids) {
            if (ids.length() > 0) {
                ids.append(',');
            }
            ids.append(String.format(Locale.ROOT, "%08x", id));
        }

        prefs.edit()
                .putString(PREF_IDS, ids.toString())
                .putInt(PREF_ACTIVE_INDEX, dials.activeIndex)
                .putInt(PREF_TOTAL, dials.total)
                .putInt(PREF_MAX, dials.max)
                .putString(PREF_RAW, dials.raw)
                .putLong(PREF_UPDATED, System.currentTimeMillis())
                .apply();
    }

    public List<Integer> getIds() {
        final List<Integer> out = new ArrayList<>();
        final String stored = prefs.getString(PREF_IDS, "");
        if (stored == null || stored.isEmpty()) {
            return out;
        }

        for (final String part : stored.split(",")) {
            try {
                // Parsed as a long first, because ids above 0x7fffffff do not fit a signed int.
                out.add((int) Long.parseLong(part.trim(), 16));
            } catch (final NumberFormatException ignored) {
                // A damaged entry only hides one line of the list.
            }
        }
        return out;
    }

    public int getActiveIndex() {
        return prefs.getInt(PREF_ACTIVE_INDEX, -1);
    }

    public int getTotal() {
        return prefs.getInt(PREF_TOTAL, 0);
    }

    public int getMax() {
        return prefs.getInt(PREF_MAX, 0);
    }

    public String getRaw() {
        return prefs.getString(PREF_RAW, "");
    }

    /** Wall clock time of the last answer, or zero when the watch was never asked. */
    public long getUpdatedAt() {
        return prefs.getLong(PREF_UPDATED, 0L);
    }

    /** True when there is a full list and no slot is left for another watchface. */
    public boolean isFull() {
        final int max = getMax();
        return max > 0 && getIds().size() >= max;
    }

    public boolean hasTarget() {
        return prefs.contains(PREF_TARGET);
    }

    /**
     * Id of the watchface that the next upload replaces. Only meaningful together with
     * {@link #hasTarget()}, because every 32 bit value is a possible id.
     */
    public int getTargetId() {
        return prefs.getInt(PREF_TARGET, 0);
    }

    public void setTargetId(final int id) {
        prefs.edit().putInt(PREF_TARGET, id).apply();
    }

    public void clearTarget() {
        prefs.edit().remove(PREF_TARGET).apply();
    }

    /** Last thing the watch said about an upload, shown on the watchface screens. */
    public String getLastResult() {
        return prefs.getString(PREF_RESULT, "");
    }

    public void setLastResult(final String text) {
        prefs.edit().putString(PREF_RESULT, text).apply();
    }
}
