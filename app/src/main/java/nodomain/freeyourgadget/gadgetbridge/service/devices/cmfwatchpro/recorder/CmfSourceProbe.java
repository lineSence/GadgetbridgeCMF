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

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.SystemClock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Diagnostics for the "the SCO link is up but every sample is zero" case.
 *
 * <p>When the watch accepts an SCO connection outside of a call but never opens its uplink,
 * the platform still hands out a perfectly valid {@code AudioRecord} that reads silence. The
 * usual culprits are the capture preset, the sample rate negotiated for the link (wideband
 * mSBC at 16 kHz versus narrowband CVSD at 8 kHz), the audio mode and which input device the
 * framework picks. This probe walks that matrix on the phone itself and prints how many
 * non-zero samples each combination produced, so the next step can be decided from data
 * instead of guesswork.</p>
 *
 * <p>{@link #run(Context, int, boolean)} blocks for roughly
 * {@code millisPerCombo * combinations} and must be called from a background thread while the
 * recorder screen is in the foreground, otherwise the microphone access is denied.</p>
 */
public final class CmfSourceProbe {
    private static final Logger LOG = LoggerFactory.getLogger(CmfSourceProbe.class);

    public static final int DEFAULT_MILLIS_PER_COMBO = 2500;

    /** Capture presets worth trying, in the order in which they are most likely to work. */
    private static final int[] SOURCES = {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT,
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.VOICE_CALL,
            MediaRecorder.AudioSource.VOICE_UPLINK,
    };

    private static final String[] SOURCE_NAMES = {
            "VOICE_COMMUNICATION",
            "VOICE_RECOGNITION",
            "MIC",
            "DEFAULT",
            "CAMCORDER",
            "VOICE_CALL",
            "VOICE_UPLINK",
    };

    /** 16 kHz is mSBC (wideband), 8 kHz is CVSD (narrowband). The watch may only do one. */
    private static final int[] SAMPLE_RATES = {16000, 8000};

    private static final String FILE_PREFIX = "cmf-probe-";

    private CmfSourceProbe() {
        // static helper
    }

    /**
     * Runs the whole matrix and returns a human readable report.
     *
     * @param millisPerCombo how long to capture per combination
     * @param inCallMode     when true, the audio mode is forced to {@code MODE_IN_CALL} instead of
     *                       {@code MODE_IN_COMMUNICATION}, which some firmwares need before they
     *                       open the microphone
     */
    public static String run(final Context context, final int millisPerCombo,
                             final boolean inCallMode) {
        final AudioManager audioManager =
                (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        final StringBuilder report = new StringBuilder();

        report.append("device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append("\nandroid=").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        report.append("mode=").append(inCallMode ? "MODE_IN_CALL" : "MODE_IN_COMMUNICATION")
                .append('\n');
        report.append("millisPerCombo=").append(millisPerCombo).append('\n');

        final CmfScoAudioLink link = new CmfScoAudioLink(context);
        report.append("scoOffCallSupported=").append(link.isScoAvailableOffCall()).append('\n');

        try {
            final boolean up = link.acquireBlocking(CmfScoAudioLink.DEFAULT_TIMEOUT_MILLIS);
            report.append("scoUp=").append(up)
                    .append("\nscoRoute=").append(link.getRoute())
                    .append("\nscoConnectMillis=").append(link.getConnectMillis())
                    .append("\nscoInputAvailable=").append(link.isScoInputAvailable())
                    .append('\n');

            if (link.getFailureReason() != null) {
                report.append("failureReason=").append(link.getFailureReason()).append('\n');
            }

            if (!up) {
                report.append("\nSCO-канал не поднялся, матрицу источников проверять бессмысленно.\n");
                return report.toString();
            }

            if (inCallMode && audioManager != null) {
                try {
                    audioManager.setMode(AudioManager.MODE_IN_CALL);
                    report.append("setModeInCall=ok, effective=")
                            .append(audioManager.getMode()).append('\n');
                } catch (final Exception e) {
                    report.append("setModeInCall=rejected: ").append(e.getMessage()).append('\n');
                }
            }

            final AudioDeviceInfo scoInput = findScoInput(audioManager);
            report.append("preferredInput=")
                    .append(scoInput != null ? describe(scoInput) : "нет SCO-входа")
                    .append("\n\n");

            report.append("источник / частота        байт    peak    rms  доля тишины\n");

            int bestPeak = 0;
            String bestCombo = null;

            for (int s = 0; s < SOURCES.length; s++) {
                for (final int sampleRate : SAMPLE_RATES) {
                    final Probe probe = probeOne(
                            SOURCES[s], sampleRate, millisPerCombo, scoInput, link);

                    report.append(String.format(
                            Locale.ROOT,
                            "%-20s %5d %8d %7d %6d %8.2f%s%n",
                            SOURCE_NAMES[s],
                            sampleRate,
                            probe.bytes,
                            probe.peak,
                            probe.rms,
                            probe.silentRatio,
                            probe.note == null ? "" : "  " + probe.note
                    ));

                    if (probe.peak > bestPeak) {
                        bestPeak = probe.peak;
                        bestCombo = SOURCE_NAMES[s] + " @ " + sampleRate + " Гц";
                    }
                }
            }

            report.append('\n');
            if (bestPeak <= 0) {
                report.append("verdict=молчат все комбинации: телефон получает SCO-кадры, "
                        + "но часы не открывают микрофон вне звонка. "
                        + "Остаётся путь через имитацию входящего звонка.\n");
            } else if (bestPeak < 328) {
                report.append(String.format(Locale.ROOT,
                        "verdict=почти тишина, максимум peak=%d на %s. "
                                + "Похоже на пустой поток с шумом квантования.%n",
                        bestPeak, bestCombo));
            } else {
                report.append(String.format(Locale.ROOT,
                        "verdict=есть звук: peak=%d на %s. Ставьте эту комбинацию по умолчанию.%n",
                        bestPeak, bestCombo));
            }
        } catch (final Exception e) {
            LOG.warn("Probe failed", e);
            report.append("probeError=").append(e).append('\n');
        } finally {
            link.release();
        }

        return report.toString();
    }

    private static Probe probeOne(final int source, final int sampleRate, final int millis,
                                  final AudioDeviceInfo preferredInput,
                                  final CmfScoAudioLink link) {
        final Probe probe = new Probe();

        final int minBufferSize = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBufferSize <= 0) {
            probe.note = "частота не поддерживается";
            return probe;
        }

        AudioRecord record = null;
        try {
            record = new AudioRecord(
                    source,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBufferSize * 4
            );

            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                probe.note = "источник недоступен";
                return probe;
            }

            if (preferredInput != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!record.setPreferredDevice(preferredInput)) {
                    probe.note = "setPreferredDevice отклонён";
                }
            }

            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                probe.note = "запись не стартовала";
                return probe;
            }

            final short[] buffer = new short[minBufferSize / 2];
            final long startedAt = SystemClock.elapsedRealtime();
            long squares = 0L;
            long samples = 0L;
            long silentSamples = 0L;

            while (SystemClock.elapsedRealtime() - startedAt < millis) {
                final int read = record.read(buffer, 0, buffer.length);
                if (read < 0) {
                    probe.note = "read вернул " + read;
                    break;
                }

                probe.bytes += read * 2;

                for (int i = 0; i < read; i++) {
                    final int value = Math.abs(buffer[i]);
                    if (value > probe.peak) {
                        probe.peak = value;
                    }
                    if (value < 328) {
                        silentSamples++;
                    }
                    squares += (long) value * value;
                    samples++;
                }
            }

            if (samples > 0L) {
                probe.rms = (int) Math.sqrt((double) squares / (double) samples);
                probe.silentRatio = (float) silentSamples / (float) samples;
            }

            if (!link.isConnected() && probe.note == null) {
                probe.note = "SCO отвалился";
            }
        } catch (final Exception e) {
            LOG.debug("Combination {}/{} failed", source, sampleRate, e);
            probe.note = "ошибка: " + e.getMessage();
        } finally {
            if (record != null) {
                try {
                    if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        record.stop();
                    }
                } catch (final Exception ignored) {
                    // already broken, nothing to salvage
                }
                record.release();
            }
        }

        return probe;
    }

    private static AudioDeviceInfo findScoInput(final AudioManager audioManager) {
        if (audioManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return null;
        }

        for (final AudioDeviceInfo device
                : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                return device;
            }
        }

        return null;
    }

    private static String describe(final AudioDeviceInfo device) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return "unknown";
        }
        return device.getProductName() + " (id " + device.getId() + ")";
    }

    /** Stores the report next to the recordings so it can be shared later. */
    public static File writeReport(final Context context, final String report) {
        final File dir = CmfMicRecorder.defaultOutputDir(context);
        if (!dir.exists() && !dir.mkdirs()) {
            LOG.warn("Could not create {}", dir);
            return null;
        }

        final String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)
                .format(new Date());
        final File file = new File(dir, FILE_PREFIX + stamp + ".txt");

        Writer writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(file), "UTF-8");
            writer.write(report);
        } catch (final IOException e) {
            LOG.warn("Could not write the probe report", e);
            return null;
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (final IOException ignored) {
                    // nothing we can do
                }
            }
        }

        return file;
    }

    private static class Probe {
        int bytes;
        int peak;
        int rms;
        float silentRatio = 1f;
        String note;
    }
}
