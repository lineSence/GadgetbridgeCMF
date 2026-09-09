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

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process-wide snapshot of what the recorder service is doing, so that the recorder screen can
 * render live state without binding to the service.
 *
 * <p>The service is the only writer; the UI only reads and subscribes.</p>
 */
public final class CmfRecorderState {
    public interface Listener {
        void onRecorderStateChanged();
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile CmfRecorderStatus.State state = CmfRecorderStatus.State.IDLE;
    private static volatile long elapsedMillis = 0L;
    private static volatile String lastReport = "";
    private static volatile String lastVerdict = "";
    private static volatile String lastFileName = "";
    private static volatile String lastExportTarget = "";

    private CmfRecorderState() {
        // utility class
    }

    public static void addListener(final Listener listener) {
        if (listener != null && !LISTENERS.contains(listener)) {
            LISTENERS.add(listener);
        }
    }

    public static void removeListener(final Listener listener) {
        LISTENERS.remove(listener);
    }

    public static void setState(final CmfRecorderStatus.State newState, final long newElapsedMillis) {
        state = newState != null ? newState : CmfRecorderStatus.State.IDLE;
        elapsedMillis = Math.max(0L, newElapsedMillis);
        notifyListeners();
    }

    public static void setResult(final String report,
                                 final String verdict,
                                 final String fileName,
                                 final String exportTarget) {
        lastReport = report != null ? report : "";
        lastVerdict = verdict != null ? verdict : "";
        lastFileName = fileName != null ? fileName : "";
        lastExportTarget = exportTarget != null ? exportTarget : "";
        notifyListeners();
    }

    public static CmfRecorderStatus.State getState() {
        return state;
    }

    public static boolean isBusy() {
        return state == CmfRecorderStatus.State.RECORDING
                || state == CmfRecorderStatus.State.CONNECTING;
    }

    public static long getElapsedMillis() {
        return elapsedMillis;
    }

    public static String getLastReport() {
        return lastReport;
    }

    public static String getLastVerdict() {
        return lastVerdict;
    }

    public static String getLastFileName() {
        return lastFileName;
    }

    public static String getLastExportTarget() {
        return lastExportTarget;
    }

    private static void notifyListeners() {
        if (LISTENERS.isEmpty()) {
            return;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                for (final Listener listener : LISTENERS) {
                    listener.onRecorderStateChanged();
                }
            }
        });
    }
}
