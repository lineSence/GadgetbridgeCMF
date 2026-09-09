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
 * Foreground service that drives the recorder engines.
 *
 * <p>Two engines share {@link CmfMicRecorder.Listener} and {@link CmfMicRecorder.Result}:
 * {@link CmfMicRecorder} writes WAV and measures the SCO link sample by sample, while
 * {@link CmfCompressedRecorder} hands the same input to MediaRecorder for AAC. Which one runs is
 * decided by {@link CmfRecorderPrefs#getFormat(Context)}, i.e. by the format picked on the
 * recorder screen.</p>
 *
 * <p>It also publishes a {@link MediaSession} while recording. That is a deliberate shortcut:
 * Gadgetbridge already mirrors the active media session to the watch
 * ({@code MUSIC_INFO_SET FFFF 905C}) and turns the watch music buttons
 * ({@code MUSIC_BUTTON FFFF A05D}) into media key events, so the recorder gets a status line on
 * the watch and a start/stop control without touching the device support class.</p>
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

    private CmfMicRecorder wavRecorder;
    private CmfCompressedRecorder compressedRecorder;
    private MediaSession mediaSession;
    private CmfRecorderStatus.State state = CmfRecorderStatus.State.IDLE;
    private long elapsedMillis;

    /** Whether a recording session is currently owned by this service. */
    public static boolean isRecordingSessionActive() {
        return sessionActive;
    }

    /** Convenience entry point for other components, for example the device support. */
    public static void dispatch(final Context context, final String action) {
        dispatch(context, action, -1, false);
    }

    /**
     * Starts or stops the recorder.
     *
     * @param durationSeconds stop automatically after this long, 0 for no limit, negative to use
     *                        whatever is configured on the recorder screen
     * @param usePhoneMic     control run: record the phone microphone without touching SCO
     */
    public static void dispatch(final Context context,
                                final String action,
                                final int durationSeconds,
                                final boolean usePhoneMic) {
        final Intent intent = new Intent(context, CmfMicRecorderService.class);
        intent.setAction(action);
        if (durationSeconds >= 0) {
            intent.putExtra(EXTRA_DURATION_SECONDS, durationSeconds);
        }
        if (usePhoneMic) {
            intent.putExtra(EXTRA_USE_PHONE_MIC, true);
        }

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
            CmfRecorderState.setState(newState, elapsed);
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
            // Still on the recorder thread, so the export may block.
            final String exportTarget = exportIfConfigured(result);
            publishResult(result, exportTarget);

            final StringBuilder summary = new StringBuilder(result.verdict());
            if (result.audioFile != null) {
                summary.append('\n').append(result.audioFile.getName());
            }
            if (exportTarget != null) {
                summary.append('\n').append(exportTarget);
            }
            postToMain(summary.toString());
        }

        @Override
        public void onFailed(final String reason, final CmfMicRecorder.Result partial) {
            publishResult(partial, null);
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
        wavRecorder = new CmfMicRecorder(this, recorderListener);
        compressedRecorder = new CmfCompressedRecorder(this, recorderListener);
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

        if (ACTION_STOP.equals(action) || (ACTION_TOGGLE.equals(action) && isEngineRunning())) {
            if (isEngineRunning()) {
                stopEngines();
            } else {
                finishSession("nothing was being recorded");
            }
            return START_NOT_STICKY;
        }

        if (isEngineRunning()) {
            LOG.info("Already recording, ignoring {}", action);
            return START_NOT_STICKY;
        }

        final boolean selfTest = ACTION_SELF_TEST.equals(action);
        final int configuredSeconds = CmfRecorderPrefs.getDurationSeconds(this);
        final int defaultSeconds = selfTest ? SELF_TEST_DEFAULT_SECONDS : configuredSeconds;
        final int durationSeconds = intent != null
                ? intent.getIntExtra(EXTRA_DURATION_SECONDS, defaultSeconds)
                : defaultSeconds;
        final boolean usePhoneMic = (intent != null
                && intent.getBooleanExtra(EXTRA_USE_PHONE_MIC, false))
                || (!selfTest && CmfRecorderPrefs.isPhoneMic(this));

        if (!wavRecorder.hasRecordPermission()) {
            finishSession("RECORD_AUDIO permission is not granted");
            return START_NOT_STICKY;
        }

        sessionActive = true;
        state = CmfRecorderStatus.State.CONNECTING;
        elapsedMillis = 0L;
        CmfRecorderState.setState(state, 0L);
        updateMediaSession(true);
        updateNotification();

        // The self test always uses the WAV engine, its per sample statistics are the point.
        final String format = selfTest
                ? CmfRecorderPrefs.FORMAT_WAV
                : CmfRecorderPrefs.getFormat(this);

        LOG.info("Starting recorder, format {}, duration {} s, phone mic {}",
                format, durationSeconds, usePhoneMic);

        if (CmfRecorderPrefs.FORMAT_M4A.equals(format)) {
            compressedRecorder.start(durationSeconds * 1000L, usePhoneMic);
        } else {
            wavRecorder.start(durationSeconds * 1000L, usePhoneMic);
        }

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        sessionActive = false;
        stopEngines();

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

    private boolean isEngineRunning() {
        return (wavRecorder != null && wavRecorder.isRunning())
                || (compressedRecorder != null && compressedRecorder.isRunning());
    }

    private void stopEngines() {
        if (wavRecorder != null) {
            wavRecorder.stop();
        }
        if (compressedRecorder != null) {
            compressedRecorder.stop();
        }
    }

    /** Copies the audio file, and the report next to it, into the configured output folder. */
    private String exportIfConfigured(final CmfMicRecorder.Result result) {
        final String folderUri = CmfRecorderPrefs.getFolderUri(this);
        if (folderUri == null || result == null || result.audioFile == null) {
            return null;
        }

        final String mimeType = CmfRecorderPrefs.mimeFor(
                result.audioFile.getName().endsWith(".m4a")
                        ? CmfRecorderPrefs.FORMAT_M4A
                        : CmfRecorderPrefs.FORMAT_WAV
        );

        final String target = CmfRecorderExporter.export(this, result.audioFile, mimeType, folderUri);
        if (target == null) {
            LOG.warn("Export failed, the recording stays in app storage");
            return null;
        }

        if (result.reportFile != null) {
            CmfRecorderExporter.export(this, result.reportFile, "text/plain", folderUri);
        }

        return target;
    }

    private void publishResult(final CmfMicRecorder.Result result, final String exportTarget) {
        if (result == null) {
            return;
        }

        CmfRecorderState.setResult(
                result.toReport(),
                result.verdict(),
                result.audioFile != null ? result.audioFile.getName() : "",
                exportTarget
        );
    }

    private void finishSession(final String summary) {
        LOG.info("Recorder session finished: {}", summary);

        sessionActive = false;
        state = CmfRecorderStatus.State.STOPPED;
        CmfRecorderState.setState(state, 0L);
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
            // starting from the recorder screen always works.
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

        final PendingIntent openIntent = activityPendingIntent();
        if (openIntent != null) {
            builder.setContentIntent(openIntent);
        }

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

        final PendingIntent openIntent = activityPendingIntent();
        if (openIntent != null) {
            builder.setContentIntent(openIntent);
        }

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

    private PendingIntent activityPendingIntent() {
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }

        try {
            return PendingIntent.getActivity(
                    this,
                    CmfRecorderActivity.ACTION_OPEN.hashCode(),
                    CmfRecorderActivity.openIntent(this),
                    pendingFlags
            );
        } catch (final Exception e) {
            LOG.debug("Could not build the recorder screen intent", e);
            return null;
        }
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
