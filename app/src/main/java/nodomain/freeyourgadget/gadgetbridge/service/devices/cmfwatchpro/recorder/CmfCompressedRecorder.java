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

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.os.SystemClock;

import androidx.core.content.ContextCompat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Compressed counterpart of {@link CmfMicRecorder}: records the same Bluetooth SCO input, but
 * lets {@link MediaRecorder} do the AAC encoding into an .m4a container.
 *
 * <p>Reuses {@link CmfMicRecorder.Listener} and {@link CmfMicRecorder.Result} so the service
 * does not care which engine produced the recording. Level statistics are coarser here
 * ({@link MediaRecorder#getMaxAmplitude()} polled once per 200 ms instead of per sample), which is
 * why the WAV engine stays the recommended one for the self test.</p>
 */
public class CmfCompressedRecorder {
    private static final Logger LOG = LoggerFactory.getLogger(CmfCompressedRecorder.class);

    public static final int SAMPLE_RATE = 16000;
    public static final int BIT_RATE = 64000;

    private static final long POLL_INTERVAL_MILLIS = 200L;
    private static final long TICK_INTERVAL_MILLIS = 1000L;
    private static final int SILENCE_THRESHOLD = 328;

    private final Context context;
    private final CmfMicRecorder.Listener listener;

    private volatile boolean running;
    private volatile boolean stopRequested;
    private volatile long startedElapsedMillis;

    public CmfCompressedRecorder(final Context context, final CmfMicRecorder.Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public boolean hasRecordPermission() {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    public boolean isRunning() {
        return running;
    }

    public long getElapsedMillis() {
        return running ? SystemClock.elapsedRealtime() - startedElapsedMillis : 0L;
    }

    public void stop() {
        stopRequested = true;
    }

    public synchronized void start(final long maxDurationMillis, final boolean usePhoneMic) {
        if (running) {
            LOG.warn("Compressed recorder is already running");
            return;
        }

        running = true;
        stopRequested = false;
        startedElapsedMillis = SystemClock.elapsedRealtime();

        final Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    record(maxDurationMillis, usePhoneMic);
                } catch (final Throwable t) {
                    LOG.error("Compressed recorder crashed", t);
                    final CmfMicRecorder.Result crashed = new CmfMicRecorder.Result();
                    crashed.failureReason = "recorder crashed: " + t.getMessage();
                    listener.onFailed(crashed.failureReason, crashed);
                } finally {
                    running = false;
                }
            }
        }, "CmfCompressedRecorder");
        worker.start();
    }

    @SuppressLint("MissingPermission")
    private void record(final long maxDurationMillis, final boolean usePhoneMic) {
        final CmfMicRecorder.Result result = new CmfMicRecorder.Result();
        result.startedAtMillis = System.currentTimeMillis();
        result.sampleRate = SAMPLE_RATE;

        final File outputDir = CmfMicRecorder.defaultOutputDir(context);
        final String baseName = CmfRecorderStatus.fileBaseName(result.startedAtMillis);
        result.audioFile = new File(outputDir, baseName + ".m4a");
        result.reportFile = new File(outputDir, baseName + ".txt");

        if (!hasRecordPermission()) {
            result.failureReason = "RECORD_AUDIO permission is not granted";
            writeReport(result);
            listener.onFailed(result.failureReason, result);
            return;
        }

        final CmfScoAudioLink link = new CmfScoAudioLink(context);
        result.scoOffCallSupported = link.isScoAvailableOffCall();
        result.scoInputAvailable = link.isScoInputAvailable();

        MediaRecorder mediaRecorder = null;
        boolean started = false;

        try {
            if (!usePhoneMic) {
                listener.onStateChanged(CmfRecorderStatus.State.CONNECTING, 0L);
                result.usedSco = link.acquireBlocking(CmfScoAudioLink.DEFAULT_TIMEOUT_MILLIS);
                result.scoRoute = link.getRoute();
                result.scoConnectMillis = link.getConnectMillis();

                if (!result.usedSco) {
                    result.failureReason = "SCO link did not come up: " + link.getFailureReason();
                    writeReport(result);
                    listener.onFailed(result.failureReason, result);
                    return;
                }
            }

            result.audioSource = usePhoneMic
                    ? MediaRecorder.AudioSource.MIC
                    : MediaRecorder.AudioSource.VOICE_RECOGNITION;

            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(result.audioSource);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setAudioChannels(CmfMicRecorder.CHANNELS);
            mediaRecorder.setAudioSamplingRate(SAMPLE_RATE);
            mediaRecorder.setAudioEncodingBitRate(BIT_RATE);
            mediaRecorder.setOutputFile(result.audioFile.getAbsolutePath());
            mediaRecorder.prepare();
            mediaRecorder.start();
            started = true;

            startedElapsedMillis = SystemClock.elapsedRealtime();
            listener.onStateChanged(CmfRecorderStatus.State.RECORDING, 0L);
            LOG.info("Recording AAC from source {} into {}", result.audioSource, result.audioFile);

            long lastTick = 0L;
            long lastLinkCheck = 0L;
            int maxAmplitude = 0;
            long polls = 0L;
            long silentPolls = 0L;
            double sumSquares = 0d;

            // Discard the very first reading, it is always 0 right after start().
            mediaRecorder.getMaxAmplitude();

            while (!stopRequested) {
                try {
                    Thread.sleep(POLL_INTERVAL_MILLIS);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    result.stopReason = "interrupted";
                    break;
                }

                final int amplitude = mediaRecorder.getMaxAmplitude();
                polls++;
                if (amplitude > maxAmplitude) {
                    maxAmplitude = amplitude;
                }
                if (amplitude < SILENCE_THRESHOLD) {
                    silentPolls++;
                }
                sumSquares += (double) amplitude * amplitude;

                final long elapsed = SystemClock.elapsedRealtime() - startedElapsedMillis;

                if (elapsed - lastTick >= TICK_INTERVAL_MILLIS) {
                    lastTick = elapsed;
                    listener.onStateChanged(CmfRecorderStatus.State.RECORDING, elapsed);
                }

                if (!usePhoneMic && elapsed - lastLinkCheck >= TICK_INTERVAL_MILLIS) {
                    lastLinkCheck = elapsed;
                    if (!link.isConnected()) {
                        result.stopReason = "sco lost";
                        break;
                    }
                }

                if (maxDurationMillis > 0L && elapsed >= maxDurationMillis) {
                    result.stopReason = "duration reached";
                    break;
                }
            }

            if (stopRequested) {
                result.stopReason = "stopped by user";
            }

            result.durationMillis = SystemClock.elapsedRealtime() - startedElapsedMillis;
            result.peak = maxAmplitude / 32768f;
            result.rms = polls > 0L ? (float) (Math.sqrt(sumSquares / polls) / 32768d) : 0f;
            result.silentRatio = polls > 0L ? (float) silentPolls / polls : 1f;
        } catch (final IOException e) {
            LOG.error("Could not start MediaRecorder", e);
            result.failureReason = "MediaRecorder error: " + e.getMessage();
        } catch (final RuntimeException e) {
            LOG.error("MediaRecorder refused the configuration", e);
            result.failureReason = "MediaRecorder refused to record: " + e.getMessage();
        } finally {
            if (mediaRecorder != null) {
                if (started) {
                    try {
                        mediaRecorder.stop();
                    } catch (final RuntimeException e) {
                        LOG.warn("MediaRecorder.stop failed, the file may be unusable", e);
                        if (result.failureReason == null) {
                            result.failureReason = "MediaRecorder.stop failed: " + e.getMessage();
                        }
                    }
                }
                try {
                    mediaRecorder.reset();
                    mediaRecorder.release();
                } catch (final RuntimeException e) {
                    LOG.debug("MediaRecorder.release failed", e);
                }
            }

            link.release();
        }

        if (result.audioFile != null && result.audioFile.exists()) {
            result.dataBytes = result.audioFile.length();
        }

        writeReport(result);

        if (result.failureReason != null) {
            listener.onFailed(result.failureReason, result);
        } else {
            listener.onFinished(result);
        }
    }

    private void writeReport(final CmfMicRecorder.Result result) {
        final String report = result.toReport() + "encoder=aac/m4a\n";
        LOG.info("Recorder report:\n{}", report);

        if (result.reportFile == null) {
            return;
        }

        FileOutputStream out = null;
        try {
            out = new FileOutputStream(result.reportFile);
            out.write(report.getBytes(StandardCharsets.UTF_8));
        } catch (final IOException e) {
            LOG.warn("Could not write the report file", e);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (final IOException ignored) {
                    // nothing we can do
                }
            }
        }
    }
}
