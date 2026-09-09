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
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.telecom.CallAudioState;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makes the platform announce an ongoing call to the watch so that it opens its microphone.
 *
 * <p>The recorder measurements showed that the CMF Watch Pro 2 happily accepts an SCO link
 * outside of a call, but keeps the uplink muted: every sample read back is zero, with a peak
 * of a handful of LSBs of quantisation noise. The microphone is gated on the hands-free call
 * indicators, not on the SCO connection itself, so the watch has to believe a call is in
 * progress.</p>
 *
 * <p>Instead of faking anything on the watch side, this registers a <em>self-managed</em>
 * {@link ConnectionService} with Telecom and places a VoIP call into it. Telecom then does
 * what it does for any VoIP app: it takes over the call audio, tells the connected hands-free
 * device that a call is active and routes the audio to Bluetooth. No system permissions, no
 * root and no telephony are involved; {@code MANAGE_OWN_CALLS} is granted at install time.</p>
 *
 * <p>Self-managed connections need Android 8.0 (API 26). On older releases
 * {@link #isSupported()} returns false and the recorder falls back to the plain SCO link.</p>
 */
@TargetApi(Build.VERSION_CODES.O)
public class CmfTelecomCallShim extends ConnectionService {
    private static final Logger LOG = LoggerFactory.getLogger(CmfTelecomCallShim.class);

    /** Identifier of the phone account registered for the recorder. */
    public static final String ACCOUNT_ID = "cmf-recorder";
    public static final String ACCOUNT_LABEL = "CMF recorder";

    /** Address of the placed call. It never leaves the device. */
    private static final String CALL_ADDRESS = "cmf@recorder.local";

    private static final long POLL_INTERVAL_MILLIS = 100L;

    private static volatile RecorderConnection active;
    private static volatile String lastError;

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    /** Whether the shim may place a call, i.e. whether {@code MANAGE_OWN_CALLS} is granted. */
    public static boolean hasPermission(final Context context) {
        return context.checkPermission(
                "android.permission.MANAGE_OWN_CALLS",
                android.os.Process.myPid(),
                android.os.Process.myUid()
        ) == PackageManager.PERMISSION_GRANTED;
    }

    public static PhoneAccountHandle handle(final Context context) {
        return new PhoneAccountHandle(
                new ComponentName(context.getApplicationContext(), CmfTelecomCallShim.class),
                ACCOUNT_ID
        );
    }

    /**
     * Registers the self-managed phone account. Safe to call repeatedly.
     *
     * @return null on success, otherwise a human readable reason
     */
    public static String register(final Context context) {
        if (!isSupported()) {
            return "нужен Android 8.0 или новее";
        }

        final TelecomManager telecomManager =
                (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
        if (telecomManager == null) {
            return "нет TelecomManager";
        }

        try {
            final PhoneAccount account = PhoneAccount.builder(handle(context), ACCOUNT_LABEL)
                    .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                    .addSupportedUriScheme(PhoneAccount.SCHEME_SIP)
                    .setShortDescription("Микрофон часов CMF")
                    .build();
            telecomManager.registerPhoneAccount(account);
            return null;
        } catch (final Exception e) {
            LOG.warn("Could not register the phone account", e);
            return "registerPhoneAccount: " + e.getMessage();
        }
    }

    /**
     * Places the self-managed call. The connection comes up asynchronously, so callers should
     * follow this with {@link #awaitActive(long)} from a background thread.
     *
     * @return null when the call was handed to Telecom, otherwise a human readable reason
     */
    public static String startCall(final Context context) {
        if (!isSupported()) {
            return "нужен Android 8.0 или новее";
        }
        if (!hasPermission(context)) {
            return "нет разрешения MANAGE_OWN_CALLS";
        }

        final String registerError = register(context);
        if (registerError != null) {
            return registerError;
        }

        final TelecomManager telecomManager =
                (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
        if (telecomManager == null) {
            return "нет TelecomManager";
        }

        lastError = null;

        final Bundle extras = new Bundle();
        extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle(context));
        extras.putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, false);

        try {
            telecomManager.placeCall(Uri.fromParts(PhoneAccount.SCHEME_SIP, CALL_ADDRESS, null),
                    extras);
            return null;
        } catch (final SecurityException e) {
            LOG.warn("placeCall was rejected", e);
            return "placeCall отклонён: " + e.getMessage();
        } catch (final Exception e) {
            LOG.warn("placeCall failed", e);
            return "placeCall: " + e.getMessage();
        }
    }

    /** Blocks until the connection reports itself active, or the timeout expires. */
    public static boolean awaitActive(final long timeoutMillis) {
        final long startedAt = SystemClock.elapsedRealtime();

        while (SystemClock.elapsedRealtime() - startedAt < timeoutMillis) {
            if (isActive()) {
                return true;
            }

            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        return false;
    }

    public static boolean isActive() {
        final RecorderConnection connection = active;
        return connection != null && connection.getState() == Connection.STATE_ACTIVE;
    }

    /** Which route Telecom picked for the call audio, for the report. */
    public static String describeRoute() {
        final RecorderConnection connection = active;
        if (connection == null) {
            return "нет соединения";
        }

        final CallAudioState state = connection.getCallAudioState();
        if (state == null) {
            return "маршрут неизвестен";
        }

        switch (state.getRoute()) {
            case CallAudioState.ROUTE_BLUETOOTH:
                return "bluetooth";
            case CallAudioState.ROUTE_EARPIECE:
                return "earpiece";
            case CallAudioState.ROUTE_SPEAKER:
                return "speaker";
            case CallAudioState.ROUTE_WIRED_HEADSET:
                return "wiredHeadset";
            default:
                return "route " + state.getRoute();
        }
    }

    /** Asks Telecom to route the call audio to the watch. */
    public static void routeToBluetooth() {
        final RecorderConnection connection = active;
        if (connection == null) {
            return;
        }

        try {
            connection.setAudioRoute(CallAudioState.ROUTE_BLUETOOTH);
        } catch (final Exception e) {
            LOG.warn("Could not route the call audio to Bluetooth", e);
        }
    }

    /** Ends the call. Always call this, otherwise the phone stays in a call forever. */
    public static void endCall() {
        final RecorderConnection connection = active;
        active = null;

        if (connection == null) {
            return;
        }

        try {
            connection.setDisconnected(new DisconnectCause(DisconnectCause.LOCAL));
            connection.destroy();
        } catch (final Exception e) {
            LOG.warn("Could not end the call", e);
        }
    }

    public static String getLastError() {
        return lastError;
    }

    @Override
    public Connection onCreateOutgoingConnection(final PhoneAccountHandle account,
                                                 final ConnectionRequest request) {
        final RecorderConnection connection = new RecorderConnection();
        connection.setConnectionProperties(Connection.PROPERTY_SELF_MANAGED);
        connection.setConnectionCapabilities(Connection.CAPABILITY_MUTE);
        connection.setAudioModeIsVoip(true);
        connection.setAddress(
                request != null ? request.getAddress() : null,
                TelecomManager.PRESENTATION_ALLOWED
        );
        connection.setCallerDisplayName(ACCOUNT_LABEL, TelecomManager.PRESENTATION_ALLOWED);
        connection.setActive();

        active = connection;

        try {
            connection.setAudioRoute(CallAudioState.ROUTE_BLUETOOTH);
        } catch (final Exception e) {
            LOG.warn("Could not preselect the Bluetooth route", e);
        }

        LOG.info("Self-managed recorder call is active");
        return connection;
    }

    @Override
    public void onCreateOutgoingConnectionFailed(final PhoneAccountHandle account,
                                                 final ConnectionRequest request) {
        lastError = "Telecom отказался создать соединение";
        LOG.warn("Telecom refused the outgoing connection");
    }

    /** Minimal connection: it only has to exist so that Telecom reports an ongoing call. */
    private static class RecorderConnection extends Connection {
        @Override
        public void onDisconnect() {
            setDisconnected(new DisconnectCause(DisconnectCause.LOCAL));
            destroy();
            active = null;
        }

        @Override
        public void onAbort() {
            setDisconnected(new DisconnectCause(DisconnectCause.CANCELED));
            destroy();
            active = null;
        }

        @Override
        public void onHold() {
            // A held call would mute the uplink again, so stay active.
            setActive();
        }

        @Override
        public void onUnhold() {
            setActive();
        }

        @Override
        public void onStateChanged(final int state) {
            LOG.debug("Recorder call state {}", state);
        }
    }
}
