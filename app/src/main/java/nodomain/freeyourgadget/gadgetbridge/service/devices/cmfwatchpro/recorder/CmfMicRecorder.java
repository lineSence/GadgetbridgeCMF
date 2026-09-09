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
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;

import androidx.core.content.ContextCompat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Records the CMF watch microphone into a WAV file by reading the Bluetooth SCO input.
 *
 * <p>The interesting part is not the recording itself but the measurement around it: the
 * self test writes a text report next to the audio file with the SCO route, how long the
 * link took to come up, the negotiated sample rate and simple level statistics. That is
 * enough to tell apart the three possible outcomes on real hardware:</p>
 *
 * <ul>
 *     <li>the watch refuses SCO outside of a call (no link at all),</li>
 *     <li>the link comes up but carries silence (wrong routing or a muted microphone),</li>
 *     <li>the link carries real audio from the watch.</li>
 * </ul>
 *
 * <p>All work happens on a dedicated thread; callbacks are delivered on that thread and
 * the service marshals them back to the main looper.</p>
 */
public class CmfMicRecorder {
    private static final Logger LOG = LoggerFactory.getLogger(CmfMicRecorder.class);

    /** 16 kHz is wideband (mSBC), 8 kHz is the narrowband (CVSD) fallback. */
    public static final int[] PREFERRED_SAMPLE_RATES = {16000, 8000};

    public static final int CHANNELS = 1;
    public static final int BITS_PER_SAMPLE = 16;

    /** Anything below roughly 1% of full scale counts as silence for the report. */
    private static final int SILENCE_THRESHOLD = 328;

    private static final long TICK_INTERVAL_MILLIS = 1000L;

    public interface Listener {
        void onStateChanged(CmfRecorderStatus.State state, long elapsedMillis);

        void onFinished(Result result);

        void onFailed(String reason, Result partial);
    }

    /** Everything the self test measured, also written next to the audio file. */
    public static class Result {
        public File audioFile;
        public File reportFile;
        public long startedAtMillis;
        public int sampleRate;
        public int audioSource;
        public long durationMillis;
        public long dataBytes;
        public float peak;
        public float rms;
        public float silentRatio;
        public boolean usedSco;
        public boolean scoOffCallSupported;
        public boolean scoInputAvailable;
        public String scoRoute = CmfScoAudioLink.ROUTE_NONE;
        public long scoConnectMillis = -1L;
        public String failureReason;
        public String stopReason = "unknown";

        public String toReport() {
            final StringBuilder sb = new StringBuilder();
            sb.append("# CMF microphone recorder self test\n");
            sb.append("device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
            sb.append("android=").append(Build.VERSION.RELEASE)
                    .append(" (sdk ").append(Build.VERSION.SDK_INT).append(")\n");
            sb.append("scoOffCallSupported=").append(scoOffCallSupported).append('\n');
            sb.append("headsetConnected=").append(scoInputAvailable).append('\n');
            sb.append("usedSco=").append(usedSco).append('\n');
            sb.append("scoRoute=").append(scoRoute).append('\n');
            sb.append("scoConnectMillis=").append(scoConnectMillis).append('\n');
            sb.append("audioSource=").append(audioSource).append('\n');
            sb.append("sampleRate=").append(sampleRate).append('\n');
            sb.append("channels=").append(CHANNELS).append('\n');
            sb.append("bitsPerSample=").append(BITS_PER_SAMPLE).append('\n');
            sb.append("durationMillis=").append(durationMillis).append('\n');
            sb.append("dataBytes=").append(dataBytes).append('\n');
            sb.append(String.format(Locale.ROOT, "peak=%.4f%n", peak));
            sb.append(String.format(Locale.ROOT, "rms=%.4f%n", rms));
            sb.append(String.format(Locale.ROOT, "silentRatio=%.4f%n", silentRatio));
            sb.append("stopReason=").append(stopReason).append('\n');
            if (failureReason != null) {
                sb.append("failureReason=").append(failureReason).append('\n');
            }
            if (audioFile != null) {
                sb.append("audioFile=").append(audioFile.getAbsolutePath()).append('\n');
            }
            sb.append("verdict=").append(verdict()).append('\n');
            return sb.toString();
        }

        /** Human readable one liner, also used for the final notification. */
        public String verdict() {
            if (failureReason != null) {
                return "FAILED: " + failureReason;
            }
            if (!usedSco) {
                return "recorded from the phone microphone (control run)";
            }
            if (silentRatio > 0.95f) {
                return "SCO link came up but the audio is silent";
            }
            return "SCO link carried audio, peak " + String.format(Locale.ROOT, "%.2f", peak);
        }
    }

    private final Context context;
    private final Listener listener;

    private volatile boolean running;
    private volatile boolean stopRequested;
    private volatile long startedElapsedMillis;
    private Thread worker;

    public CmfMicRecorder(final Context context, final Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    /** Where recordings and reports are written; readable over adb without root. */
    public static File defaultOutputDir(final Context context) {
        final File external = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        return external != null ? external : context.getFilesDir();
    }

    public boolean hasRecordPermission() {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    public boolean isRunning() {
        return running;
    }

    public long getElapsedMillis() {
        if (!running) {
            return 0L;
        }
        return SystemClock.elapsedRealtime() - startedElapsedMillis;
    }

    /**
     * Starts recording on a background thread.
     *
     * @param maxDurationMillis stop automatically after this long, 0 for no limit
     * @param usePhoneMic       control run: record the phone microphone without touching SCO
     */
    public synchronized void start(final long maxDurationMillis, final boolean usePhoneMic) {
        if (running) {
            LOG.warn("Recorder is already running");
            return;
        }

        running = true;
        stopRequested = false;
        startedElapsedMillis = SystemClock.elapsedRealtime();

        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    record(maxDurationMillis, usePhoneMic);
                } catch (final Throwable t) {
                    LOG.error("Recorder crashed", t);
                    final Result crashed = new Result();
                    crashed.failureReason = String.valueOf(t.getMessage());
                    listener.onFailed("recorder crashed: " + t.getMessage(), crashed);
                } finally {
                    running = false;
                }
            }
        }, "CmfMicRecorder");
        worker.start();
    }

    public void stop() {
        stopRequested = true;
    }

    @SuppressLint("MissingPermission")
    private void record(final long maxDurationMillis, final boolean usePhoneMic) {
        final Result result = new Result();
        result.startedAtMillis = System.currentTimeMillis();

        final File outputDir = defaultOutputDir(context);
        final String baseName = CmfRecorderStatus.fileBaseName(result.startedAtMillis);
        result.audioFile = new File(outputDir, baseName + ".wav");
        result.reportFile = new File(outputDir, baseName + ".txt");

        if (!hasRecordPermission()) {
            result.failureReason = "RECORD_AUDIO permission is not granted";
            finishWithFailure(result);
            return;
        }

        final CmfScoAudioLink link = new CmfScoAudioLink(context);
        result.scoOffCallSupported = link.isScoAvailableOffCall();
        result.scoInputAvailable = link.isScoInputAvailable();

        AudioRecord audioRecord = null;
        CmfWavWriter writer = null;

        try {
            if (!usePhoneMic) {
                listener.onStateChanged(CmfRecorderStatus.State.CONNECTING, 0L);
                result.usedSco = link.acquireBlocking(CmfScoAudioLink.DEFAULT_TIMEOUT_MILLIS);
                result.scoRoute = link.getRoute();
                result.scoConnectMillis = link.getConnectMillis();

                if (!result.usedSco) {
                    result.failureReason = "SCO link did not come up: " + link.getFailureReason();
                    finishWithFailure(result);
                    return;
                }
            }

            result.audioSource = usePhoneMic
                    ? MediaRecorder.AudioSource.MIC
                    : MediaRecorder.AudioSource.VOICE_RECOGNITION;

            int bufferSize = 0;
            for (final int sampleRate : PREFERRED_SAMPLE_RATES) {
                final int minBufferSize = AudioRecord.getMinBufferSize(
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                );
                if (minBufferSize <= 0) {
                    LOG.debug("Sample rate {} is not supported ({})", sampleRate, minBufferSize);
                    continue;
                }

                final int candidateBuffer = Math.max(minBufferSize * 2, sampleRate / 5 * 2);
                final AudioRecord candidate = new AudioRecord(
                        result.audioSource,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        candidateBuffer
                );

                if (candidate.getState() == AudioRecord.STATE_INITIALIZED) {
                    audioRecord = candidate;
                    bufferSize = candidateBuffer;
                    result.sampleRate = sampleRate;
                    break;
                }

                LOG.debug("AudioRecord did not initialise at {} Hz", sampleRate);
                candidate.release();
            }

            if (audioRecord == null) {
                result.failureReason = "AudioRecord could not be initialised at any sample rate";
                finishWithFailure(result);
                return;
            }

            writer = new CmfWavWriter(result.audioFile, result.sampleRate, CHANNELS, BITS_PER_SAMPLE);

            audioRecord.startRecording();
            if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                result.failureReason = "AudioRecord refused to start";
                finishWithFailure(result);
                return;
            }

            startedElapsedMillis = SystemClock.elapsedRealtime();
            listener.onStateChanged(CmfRecorderStatus.State.RECORDING, 0L);
            LOG.info("Recording from source {} at {} Hz into {}",
                    result.audioSource, result.sampleRate, result.audioFile);

            final byte[] buffer = new byte[bufferSize];
            long totalSamples = 0L;
            long silentSamples = 0L;
            double sumSquares = 0d;
            int maxAbs = 0;
            long lastTick = 0L;
            long lastLinkCheck = 0L;

            while (!stopRequested) {
                final int read = audioRecord.read(buffer, 0, buffer.length);
                if (read < 0) {
                    result.failureReason = "AudioRecord.read returned " + read;
                    break;
                }
                if (read == 0) {
                    continue;
                }

                writer.write(buffer, 0, read);

                for (int i = 0; i + 1 < read; i += 2) {
                    final int sample = (short) ((buffer[i] & 0xff) | (buffer[i + 1] << 8));
                    final int abs = Math.abs(sample);
                    if (abs > maxAbs) {
                        maxAbs = abs;
                    }
                    if (abs < SILENCE_THRESHOLD) {
                        silentSamples++;
                    }
                    sumSquares += (double) sample * sample;
                    totalSamples++;
                }

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

            result.dataBytes = writer.getDataBytes();
            result.durationMillis = writer.getDurationMillis();
            result.peak = maxAbs / 32768f;
            result.rms = totalSamples > 0L
                    ? (float) (Math.sqrt(sumSquares / totalSamples) / 32768d)
                    : 0f;
            result.silentRatio = totalSamples > 0L ? (float) silentSamples / totalSamples : 1f;
        } catch (final IOException e) {
            LOG.error("Could not write the recording", e);
            result.failureReason = "io error: " + e.getMessage();
        } finally {
            if (audioRecord != null) {
                try {
                    if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        audioRecord.stop();
                    }
                } catch (final Exception e) {
                    LOG.debug("AudioRecord.stop failed", e);
                }
                audioRecord.release();
            }

            if (writer != null) {
                try {
                    writer.close();
                } catch (final IOException e) {
                    LOG.warn("Could not finalise the WAV header", e);
                }
            }

            link.release();
        }

        writeReport(result);

        if (result.failureReason != null) {
            listener.onFailed(result.failureReason, result);
        } else {
            listener.onFinished(result);
        }
    }

    private void finishWithFailure(final Result result) {
        LOG.warn("Recording failed: {}", result.failureReason);
        writeReport(result);
        listener.onFailed(result.failureReason, result);
    }

    private void writeReport(final Result result) {
        final String report = result.toReport();
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
