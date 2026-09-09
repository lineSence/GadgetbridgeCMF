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

import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Brings up the Bluetooth SCO (HFP) audio link to the watch so that its microphone and
 * speaker can be used outside of a phone call.
 *
 * <p>The CMF Watch Pro 2 exposes a hands-free profile for Bluetooth calls. That same
 * profile is the only supported way to reach its microphone without touching the
 * firmware, so the recorder asks the platform to route communication audio to the watch
 * and then reads from it with a regular {@code AudioRecord}.</p>
 *
 * <p>Two routes are implemented:</p>
 * <ul>
 *     <li>Android 12+ (API 31): {@code AudioManager.setCommunicationDevice()} with the
 *     {@code TYPE_BLUETOOTH_SCO} device, which is the supported replacement for the
 *     deprecated SCO calls.</li>
 *     <li>Older releases: {@code startBluetoothSco()} plus the
 *     {@code ACTION_SCO_AUDIO_STATE_UPDATED} broadcast.</li>
 * </ul>
 *
 * <p>Whether the watch actually accepts an SCO connection outside of a call is a
 * property of its firmware and is exactly what the recorder self test measures.</p>
 *
 * <p>{@link #acquireBlocking(long)} blocks and must be called from a background thread.
 * {@link #release()} must always run, ideally from a {@code finally} block, otherwise the
 * phone stays in communication mode and every other audio stream keeps being ducked.</p>
 */
public class CmfScoAudioLink {
    private static final Logger LOG = LoggerFactory.getLogger(CmfScoAudioLink.class);

    public static final long DEFAULT_TIMEOUT_MILLIS = 8000L;

    public static final String ROUTE_NONE = "none";
    public static final String ROUTE_COMMUNICATION_DEVICE = "communicationDevice";
    public static final String ROUTE_LEGACY_SCO = "legacyStartBluetoothSco";

    private static final long POLL_INTERVAL_MILLIS = 100L;

    private final Context context;
    private final AudioManager audioManager;
    private final Handler handler;
    private final CountDownLatch connectedLatch = new CountDownLatch(1);

    private volatile boolean connected;
    private volatile String failureReason;

    private BroadcastReceiver receiver;
    private boolean acquired;
    private int previousMode = AudioManager.MODE_NORMAL;
    private String route = ROUTE_NONE;
    private long connectMillis = -1L;

    public CmfScoAudioLink(final Context context) {
        this.context = context.getApplicationContext();
        this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        this.handler = new Handler(Looper.getMainLooper());
    }

    /** Whether the platform allows SCO to be used while no call is active. */
    public boolean isScoAvailableOffCall() {
        return audioManager != null && audioManager.isBluetoothScoAvailableOffCall();
    }

    /**
     * Whether a Bluetooth SCO input is currently known to the audio framework, which is a
     * good proxy for "the watch is connected as a headset, not only over BLE".
     */
    @TargetApi(Build.VERSION_CODES.M)
    public boolean isScoInputAvailable() {
        if (audioManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }

        for (final AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                return true;
            }
        }

        return false;
    }

    /**
     * Requests the SCO link and blocks until it is up or the timeout expires.
     *
     * @return true when communication audio is routed to the watch
     */
    public boolean acquireBlocking(final long timeoutMillis) {
        if (audioManager == null) {
            failureReason = "no AudioManager";
            return false;
        }
        if (acquired) {
            return isConnected();
        }

        acquired = true;
        final long startedAt = SystemClock.elapsedRealtime();

        try {
            previousMode = audioManager.getMode();
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        } catch (final Exception e) {
            LOG.warn("Could not switch the audio mode", e);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            route = ROUTE_COMMUNICATION_DEVICE;
            return acquireCommunicationDevice(startedAt, timeoutMillis);
        }

        route = ROUTE_LEGACY_SCO;
        return acquireLegacySco(startedAt, timeoutMillis);
    }

    @TargetApi(Build.VERSION_CODES.S)
    private boolean acquireCommunicationDevice(final long startedAt, final long timeoutMillis) {
        AudioDeviceInfo target = null;

        final List<AudioDeviceInfo> available = audioManager.getAvailableCommunicationDevices();
        for (final AudioDeviceInfo device : available) {
            if (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                target = device;
                break;
            }
        }

        if (target == null) {
            failureReason = "no TYPE_BLUETOOTH_SCO communication device (" + available.size() + " available)";
            return false;
        }

        LOG.info("Routing communication audio to {}", target.getProductName());

        if (!audioManager.setCommunicationDevice(target)) {
            failureReason = "setCommunicationDevice was rejected";
            return false;
        }

        while (SystemClock.elapsedRealtime() - startedAt < timeoutMillis) {
            if (isCommunicationDeviceSco()) {
                connected = true;
                connectMillis = SystemClock.elapsedRealtime() - startedAt;
                connectedLatch.countDown();
                return true;
            }

            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                failureReason = "interrupted while waiting for the SCO route";
                return false;
            }
        }

        failureReason = "communication device did not switch to SCO within " + timeoutMillis + " ms";
        return false;
    }

    private boolean acquireLegacySco(final long startedAt, final long timeoutMillis) {
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(final Context ignored, final Intent intent) {
                final int state = intent.getIntExtra(
                        AudioManager.EXTRA_SCO_AUDIO_STATE,
                        AudioManager.SCO_AUDIO_STATE_ERROR
                );
                LOG.debug("SCO audio state is now {}", state);

                if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                    connected = true;
                    connectedLatch.countDown();
                    return;
                }

                if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED
                        || state == AudioManager.SCO_AUDIO_STATE_ERROR) {
                    connected = false;
                    if (failureReason == null && connectedLatch.getCount() > 0L) {
                        failureReason = "SCO audio state " + state + " before the link came up";
                    }
                }
            }
        };

        context.registerReceiver(
                receiver,
                new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
                null,
                handler
        );

        try {
            audioManager.setBluetoothScoOn(true);
            audioManager.startBluetoothSco();
        } catch (final Exception e) {
            failureReason = "startBluetoothSco failed: " + e.getMessage();
            return false;
        }

        try {
            if (!connectedLatch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                if (failureReason == null) {
                    failureReason = "no SCO_AUDIO_STATE_CONNECTED within " + timeoutMillis + " ms";
                }
                return false;
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            failureReason = "interrupted while waiting for SCO";
            return false;
        }

        connectMillis = SystemClock.elapsedRealtime() - startedAt;
        return connected;
    }

    /** Whether communication audio is still routed to the watch. */
    public boolean isConnected() {
        if (!acquired || audioManager == null) {
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return isCommunicationDeviceSco();
        }

        return connected;
    }

    @TargetApi(Build.VERSION_CODES.S)
    private boolean isCommunicationDeviceSco() {
        final AudioDeviceInfo current = audioManager.getCommunicationDevice();
        return current != null && current.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO;
    }

    /** Releases the link and restores the previous audio mode. Safe to call twice. */
    public void release() {
        if (!acquired) {
            return;
        }

        acquired = false;
        connected = false;

        if (receiver != null) {
            try {
                context.unregisterReceiver(receiver);
            } catch (final Exception e) {
                LOG.debug("Receiver was not registered", e);
            }
            receiver = null;
        }

        if (audioManager == null) {
            return;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice();
            } else {
                audioManager.stopBluetoothSco();
                audioManager.setBluetoothScoOn(false);
            }
        } catch (final Exception e) {
            LOG.warn("Could not release the SCO link", e);
        }

        try {
            audioManager.setMode(previousMode);
        } catch (final Exception e) {
            LOG.warn("Could not restore the audio mode", e);
        }
    }

    /** Which of the two routes was used, for the self test report. */
    public String getRoute() {
        return route;
    }

    /** How long the link took to come up, in milliseconds, or -1 if it never did. */
    public long getConnectMillis() {
        return connectMillis;
    }

    public String getFailureReason() {
        return failureReason;
    }
}
