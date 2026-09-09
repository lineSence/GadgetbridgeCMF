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

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Pure java helpers shared by the recorder service, the phone notification and the
 * watch-side status line.
 *
 * <p>The watch has no recorder UI of its own, so the recorder state is pushed to the
 * music screen ({@code MUSIC_INFO_SET FFFF 905C}), whose track and artist fields are
 * 64 bytes each. Everything in here is deliberately free of Android dependencies so
 * it can be covered by plain JVM unit tests.</p>
 */
public final class CmfRecorderStatus {
    /** Artist field shown on the watch music screen while recording. */
    public static final String WATCH_ARTIST = "CMF recorder";

    /** Size of the track/artist fields in the {@code MUSIC_INFO_SET} payload. */
    public static final int WATCH_TEXT_FIELD_SIZE = 64;

    /** Recording indicator, kept short so the timer stays visible on the watch. */
    public static final String PREFIX_RECORDING = "REC \u25cf ";

    /** Shown while the SCO link is being negotiated. */
    public static final String PREFIX_CONNECTING = "REC \u2026 ";

    /** Shown after a manual pause / stop, before the service goes away. */
    public static final String PREFIX_STOPPED = "STOP ";

    public static final String FILE_PREFIX = "cmf-rec-";

    private CmfRecorderStatus() {
        // utility class
    }

    /**
     * Formats an elapsed duration as {@code mm:ss}, or {@code h:mm:ss} once the recording
     * passes the one hour mark. Negative values are clamped to zero.
     */
    public static String formatElapsed(final long millis) {
        final long totalSeconds = millis > 0 ? millis / 1000L : 0L;
        final long hours = totalSeconds / 3600L;
        final long minutes = (totalSeconds % 3600L) / 60L;
        final long seconds = totalSeconds % 60L;

        if (hours > 0) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
        }

        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
    }

    /** Title for the watch music screen and for the phone notification. */
    public static String watchTitle(final State state, final long elapsedMillis) {
        switch (state) {
            case CONNECTING:
                return PREFIX_CONNECTING + formatElapsed(elapsedMillis);
            case RECORDING:
                return PREFIX_RECORDING + formatElapsed(elapsedMillis);
            default:
                return PREFIX_STOPPED + formatElapsed(elapsedMillis);
        }
    }

    /** Base file name (without extension) for a recording started at the given time. */
    public static String fileBaseName(final long timestampMillis) {
        final SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT);
        return FILE_PREFIX + format.format(new Date(timestampMillis));
    }

    /** Truncates a string so that it fits into one of the watch text fields. */
    public static String truncateForWatch(final String text) {
        // one byte is reserved for the trailing NUL the firmware expects
        return truncateUtf8(text, WATCH_TEXT_FIELD_SIZE - 1);
    }

    /**
     * Truncates a string to at most {@code maxBytes} bytes of UTF-8, never splitting a
     * character in half. Returns an empty string for {@code null} input.
     */
    public static String truncateUtf8(final String text, final int maxBytes) {
        if (text == null || text.isEmpty() || maxBytes <= 0) {
            return "";
        }

        String candidate = text;
        while (candidate.getBytes(StandardCharsets.UTF_8).length > maxBytes && !candidate.isEmpty()) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }

        return candidate;
    }

    public enum State {
        IDLE,
        CONNECTING,
        RECORDING,
        STOPPED,
    }
}
