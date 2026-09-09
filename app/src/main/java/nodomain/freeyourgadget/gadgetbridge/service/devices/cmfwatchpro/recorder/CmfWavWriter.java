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

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * Minimal RIFF/WAVE writer for raw PCM captured from the watch microphone.
 *
 * <p>The header is written up front with zero sizes and patched with the real sizes on
 * {@link #close()}, so a recording that is killed mid-way still leaves a file that most
 * players can open (only the trailing chunk sizes are missing).</p>
 *
 * <p>Deliberately Android free so it can be unit tested on the JVM.</p>
 */
public class CmfWavWriter implements Closeable {
    /** Canonical 44 byte PCM header: RIFF + fmt + data. */
    public static final int HEADER_SIZE = 44;

    private static final int FORMAT_PCM = 1;
    private static final int FMT_CHUNK_SIZE = 16;

    private final int sampleRate;
    private final int channels;
    private final int bitsPerSample;
    private final File target;
    private final RandomAccessFile out;

    private long dataBytes;
    private boolean closed;

    public CmfWavWriter(final File target,
                        final int sampleRate,
                        final int channels,
                        final int bitsPerSample) throws IOException {
        if (sampleRate <= 0 || channels <= 0 || bitsPerSample <= 0) {
            throw new IllegalArgumentException("invalid pcm format");
        }

        this.target = target;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.bitsPerSample = bitsPerSample;

        final File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("could not create " + parent);
        }

        this.out = new RandomAccessFile(target, "rw");
        this.out.setLength(0L);
        this.out.write(buildHeader(sampleRate, channels, bitsPerSample, 0L));
    }

    /** Builds a 44 byte PCM WAV header for the given format and payload size. */
    public static byte[] buildHeader(final int sampleRate,
                                     final int channels,
                                     final int bitsPerSample,
                                     final long dataBytes) {
        final int blockAlign = channels * bitsPerSample / 8;
        final int byteRate = sampleRate * blockAlign;

        final byte[] header = new byte[HEADER_SIZE];
        putAscii(header, 0, "RIFF");
        putIntLe(header, 4, (int) (HEADER_SIZE - 8 + dataBytes));
        putAscii(header, 8, "WAVE");
        putAscii(header, 12, "fmt ");
        putIntLe(header, 16, FMT_CHUNK_SIZE);
        putShortLe(header, 20, FORMAT_PCM);
        putShortLe(header, 22, channels);
        putIntLe(header, 24, sampleRate);
        putIntLe(header, 28, byteRate);
        putShortLe(header, 32, blockAlign);
        putShortLe(header, 34, bitsPerSample);
        putAscii(header, 36, "data");
        putIntLe(header, 40, (int) dataBytes);
        return header;
    }

    /** Duration of a PCM payload, in milliseconds. */
    public static long durationMillis(final long dataBytes,
                                      final int sampleRate,
                                      final int channels,
                                      final int bitsPerSample) {
        final long bytesPerSecond = (long) sampleRate * channels * bitsPerSample / 8L;
        if (bytesPerSecond <= 0L) {
            return 0L;
        }
        return dataBytes * 1000L / bytesPerSecond;
    }

    public void write(final byte[] buffer, final int offset, final int length) throws IOException {
        if (closed) {
            throw new IOException("writer already closed");
        }
        if (length <= 0) {
            return;
        }
        out.write(buffer, offset, length);
        dataBytes += length;
    }

    public long getDataBytes() {
        return dataBytes;
    }

    public long getDurationMillis() {
        return durationMillis(dataBytes, sampleRate, channels, bitsPerSample);
    }

    public File getTarget() {
        return target;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public int getChannels() {
        return channels;
    }

    public int getBitsPerSample() {
        return bitsPerSample;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;

        try {
            out.seek(0L);
            out.write(buildHeader(sampleRate, channels, bitsPerSample, dataBytes));
        } finally {
            out.close();
        }
    }

    private static void putAscii(final byte[] target, final int offset, final String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, target, offset, bytes.length);
    }

    private static void putIntLe(final byte[] target, final int offset, final int value) {
        target[offset] = (byte) (value & 0xff);
        target[offset + 1] = (byte) ((value >> 8) & 0xff);
        target[offset + 2] = (byte) ((value >> 16) & 0xff);
        target[offset + 3] = (byte) ((value >> 24) & 0xff);
    }

    private static void putShortLe(final byte[] target, final int offset, final int value) {
        target[offset] = (byte) (value & 0xff);
        target[offset + 1] = (byte) ((value >> 8) & 0xff);
    }
}
