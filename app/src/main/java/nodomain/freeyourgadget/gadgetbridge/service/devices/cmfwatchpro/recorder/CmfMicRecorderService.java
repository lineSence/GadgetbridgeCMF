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
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Foreground service that drives {@link CmfMicRecorder}.
 *
 * <p>It also publishes a {@link MediaSession} while recording. That is a deliberate
 * shortcut: Gadgetbridge already mirrors the active media session to the watch
 * ({@code MUSIC_INFO_SET FFFF 905C}) and turns the watch music buttons
 * ({@code MUSIC_BUTTON FFFF A05D}) into media key events, so the recorder gets a status
 * line on the watch and a start/stop control without touching the device support class.
 * A direct hook in {@code CmfWatchProSupport} remains the deterministic follow up.</p>
 *
 * <p>The service is declared in the debug source set only, see
 * {@code app/src/debug/AndroidManifest.xml} and {@code docs/CMF_MIC_RECORDER.md}.</p>
 */
public class CmfMicRecorderService extends Service {
    private static final Logger LOG = LoggerFactory.getLogger(CmfMicRecorderService.class);

    public static final String ACTION_START = "nodomain.freeyourgadget.gadgetbridge.cmf.recorder.START";
    public static final String ACTION_STOP = "nodomain.freeyourgadget.gadgetbridge.cmf.recorder.STOP";
    public static final String ACTION_TOGGLE = "nodomain.freeyourgadget.gadgetbridge.cmf.recorder.TOGGLE";
    public static final String ACTION_SELF_TEST = "nodomain.freeyourgadget.gadgetbridge.cmf.recorder.SELFTEST";

    public static final String EXTRA_DURATION_SECONDS = "duration_seconds";
    public static final String EXTRA_USE_PHONE_MIC = "use_phone_mic";

    private static final String CHANNEL_ID = "cmf_mic_recorder";
    private static final int NOTIFICATION_ID = 1974;
    private static final int NOTIFICATION_ID_RESULT = 1975;
    private static final int SELF_TEST_DEFAULT_SECONDS = 10;

    private static volatile boolean sessionActive;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private CmfMicRecorder recorder;
    private MediaSession mediaSession;
    private CmfRecorderStatus.State state = CmfRecorderStatus.State.IDLE;
    private long elapsedMillis;

    /** Whether a recording session is currently owned by this service. */
    public static boolean isRecordingSessionActive() {
        return sessionActive;
    }

    /** Convenience entry point for other components, for example the device support. */
    public static void dispatch(final Context context, final String action) {
        final Intent intent = new Intent(context, CmfMicRecorderService.class);
        intent.setAction(action);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (final Exception e) {
            LOG.error("Could not start the recorder service", e);
        }
    }

    private final CmfMicRecorder.Listener recorderListener = new CmfMicRecorder.Listener() {
        @Override
        public void onStateChanged(final CmfRecorderStatus.State newState, final long elapsed) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    state = newState;
                    elapsedMillis = elapsed;
                    updateMediaSession(true);
                    updateNotification();
                }
            });
        }

        @Override
        public void onFinished(final CmfMicRecorder.Result result) {
            final String summary = result.verdict()
                    + (result.audioFile != null ? "\n" + result.audioFile.getName() : "");
            postToMain(summary);
        }

        @Override
        public void onFailed(final String reason, final CmfMicRecorder.Result partial) {
            postToMain("FAILED: " + reason);
        }

        private void postToMain(final String summary) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    finishSession(summary);
                }
            });
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        recorder = new CmfMicRecorder(this, recorderListener);
        createNotificationChannel();
        setupMediaSession();
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        final String action = intent != null && intent.getAction() != null
                ? intent.getAction()
                : ACTION_START;
        LOG.info("Recorder service received {}", action);

        startForegroundSafely();

        if (ACTION_STOP.equals(action) || (ACTION_TOGGLE.equals(action) && recorder.isRunning())) {
            if (recorder.isRunning()) {
                recorder.stop();
            } else {
                finishSession("nothing was being recorded");
            }
            return START_NOT_STICKY;
        }

        if (recorder.isRunning()) {
            LOG.info("Already recording, ignoring {}", action);
            return START_NOT_STICKY;
        }

        final boolean selfTest = ACTION_SELF_TEST.equals(action);
        final int defaultSeconds = selfTest ? SELF_TEST_DEFAULT_SECONDS : 0;
        final int durationSeconds = intent != null
                ? intent.getIntExtra(EXTRA_DURATION_SECONDS, defaultSeconds)
                : defaultSeconds;
        final boolean usePhoneMic = intent != null
                && intent.getBooleanExtra(EXTRA_USE_PHONE_MIC, false);

        if (!recorder.hasRecordPermission()) {
            finishSession("RECORD_AUDIO permission is not granted");
            return START_NOT_STICKY;
        }

        sessionActive = true;
        state = CmfRecorderStatus.State.CONNECTING;
        elapsedMillis = 0L;
        updateMediaSession(true);
        updateNotification();

        LOG.info("Starting recorder, duration {} s, phone mic {}", durationSeconds, usePhoneMic);
        recorder.start(durationSeconds * 1000L, usePhoneMic);

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        sessionActive = false;

        if (recorder != null) {
            recorder.stop();
        }

        if (mediaSession != null) {
            try {
                mediaSession.setActive(false);
                mediaSession.release();
            } catch (final Exception e) {
                LOG.debug("Could not release the media session", e);
            }
            mediaSession = null;
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(final Intent intent) {
        return null;
    }

    private void finishSession(final String summary) {
        LOG.info("Recorder session finished: {}", summary);

        sessionActive = false;
        state = CmfRecorderStatus.State.STOPPED;
        updateMediaSession(false);
        postResultNotification(summary);

        stopForeground(true);
        stopSelf();
    }

    private void startForegroundSafely() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                        NOTIFICATION_ID,
                        buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                );
            } else {
                startForeground(NOTIFICATION_ID, buildNotification());
            }
        } catch (final Exception e) {
            // Android 12+ refuses background starts of microphone services in some states;
            // open Gadgetbridge in the foreground first, then retry.
            LOG.error("Could not enter the foreground", e);
        }
    }

    private void updateNotification() {
        final NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }

        try {
            manager.notify(NOTIFICATION_ID, buildNotification());
        } catch (final Exception e) {
            LOG.debug("Could not update the notification", e);
        }
    }

    private void postResultNotification(final String summary) {
        final NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }

        final Notification.Builder builder = newNotificationBuilder();
        builder.setContentTitle("CMF recorder")
                .setContentText(summary)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setStyle(new Notification.BigTextStyle().bigText(summary))
                .setAutoCancel(true);

        try {
            manager.notify(NOTIFICATION_ID_RESULT, builder.build());
        } catch (final Exception e) {
            LOG.debug("Could not post the result notification", e);
        }
    }

    private Notification buildNotification() {
        final Notification.Builder builder = newNotificationBuilder();

        builder.setContentTitle(CmfRecorderStatus.watchTitle(state, elapsedMillis))
                .setContentText(CmfRecorderStatus.WATCH_ARTIST)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC);

        final PendingIntent stopIntent = servicePendingIntent(ACTION_STOP);
        if (stopIntent != null) {
            builder.addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent);
        }

        if (mediaSession != null) {
            builder.setStyle(new Notification.MediaStyle().setMediaSession(mediaSession.getSessionToken()));
        }

        return builder.build();
    }

    private Notification.Builder newNotificationBuilder() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, CHANNEL_ID);
        }
        return new Notification.Builder(this);
    }

    private PendingIntent servicePendingIntent(final String action) {
        final Intent intent = new Intent(this, CmfMicRecorderService.class);
        intent.setAction(action);

        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                return PendingIntent.getForegroundService(this, action.hashCode(), intent, pendingFlags);
            }
            return PendingIntent.getService(this, action.hashCode(), intent, pendingFlags);
        } catch (final Exception e) {
            LOG.debug("Could not build a pending intent for {}", action, e);
            return null;
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        final NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }

        final NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "CMF recorder",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Recording from the watch microphone");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private void setupMediaSession() {
        try {
            mediaSession = new MediaSession(this, "CmfMicRecorder");
            mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                    | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            mediaSession.setCallback(new MediaSession.Callback() {
                @Override
                public void onPlay() {
                    LOG.info("Media play from the watch");
                    dispatch(CmfMicRecorderService.this, ACTION_START);
                }

                @Override
                public void onPause() {
                    LOG.info("Media pause from the watch");
                    dispatch(CmfMicRecorderService.this, ACTION_STOP);
                }

                @Override
                public void onStop() {
                    LOG.info("Media stop from the watch");
                    dispatch(CmfMicRecorderService.this, ACTION_STOP);
                }

                @Override
                public void onSkipToNext() {
                    LOG.info("Marker requested from the watch at {}",
                            CmfRecorderStatus.formatElapsed(elapsedMillis));
                }

                @Override
                public void onSkipToPrevious() {
                    LOG.info("Marker requested from the watch at {}",
                            CmfRecorderStatus.formatElapsed(elapsedMillis));
                }
            });
        } catch (final Exception e) {
            LOG.warn("Could not create the media session", e);
            mediaSession = null;
        }
    }

    private void updateMediaSession(final boolean active) {
        if (mediaSession == null) {
            return;
        }

        try {
            mediaSession.setActive(active);

            final MediaMetadata metadata = new MediaMetadata.Builder()
                    .putString(
                            MediaMetadata.METADATA_KEY_TITLE,
                            CmfRecorderStatus.truncateForWatch(
                                    CmfRecorderStatus.watchTitle(state, elapsedMillis))
                    )
                    .putString(
                            MediaMetadata.METADATA_KEY_ARTIST,
                            CmfRecorderStatus.truncateForWatch(CmfRecorderStatus.WATCH_ARTIST)
                    )
                    .build();
            mediaSession.setMetadata(metadata);

            final int playbackState = active && state == CmfRecorderStatus.State.RECORDING
                    ? PlaybackState.STATE_PLAYING
                    : PlaybackState.STATE_PAUSED;

            mediaSession.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY
                            | PlaybackState.ACTION_PAUSE
                            | PlaybackState.ACTION_PLAY_PAUSE
                            | PlaybackState.ACTION_STOP
                            | PlaybackState.ACTION_SKIP_TO_NEXT)
                    .setState(playbackState, elapsedMillis, 1.0f)
                    .build());
        } catch (final Exception e) {
            LOG.warn("Could not update the media session", e);
        }
    }
}
