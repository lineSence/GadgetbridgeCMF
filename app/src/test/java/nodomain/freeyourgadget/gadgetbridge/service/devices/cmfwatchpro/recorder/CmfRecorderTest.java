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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * Unit tests for the Android free parts of the CMF microphone recorder.
 */
public class CmfRecorderTest {

    @Test
    public void wavHeaderFollowsTheRiffSpec() {
        final byte[] header = CmfWavWriter.buildHeader(16000, 1, 16, 32000L);

        assertEquals(CmfWavWriter.HEADER_SIZE, header.length);
        assertEquals("RIFF", ascii(header, 0, 4));
        assertEquals(36 + 32000, intLe(header, 4));
        assertEquals("WAVE", ascii(header, 8, 4));
        assertEquals("fmt ", ascii(header, 12, 4));
        assertEquals(16, intLe(header, 16));
        assertEquals(1, shortLe(header, 20)); // PCM
        assertEquals(1, shortLe(header, 22)); // mono
        assertEquals(16000, intLe(header, 24));
        assertEquals(32000, intLe(header, 28)); // byte rate
        assertEquals(2, shortLe(header, 32)); // block align
        assertEquals(16, shortLe(header, 34));
        assertEquals("data", ascii(header, 36, 4));
        assertEquals(32000, intLe(header, 40));
    }

    @Test
    public void wavHeaderIsPatchedOnClose() throws IOException {
        final File target = File.createTempFile("cmf-rec-test", ".wav");
        target.deleteOnExit();

        final CmfWavWriter writer = new CmfWavWriter(target, 8000, 1, 16);
        writer.write(new byte[100], 0, 100);
        assertEquals(100L, writer.getDataBytes());
        writer.close();

        assertEquals(CmfWavWriter.HEADER_SIZE + 100L, target.length());

        final byte[] header = new byte[CmfWavWriter.HEADER_SIZE];
        final RandomAccessFile in = new RandomAccessFile(target, "r");
        try {
            in.readFully(header);
        } finally {
            in.close();
        }

        assertEquals(36 + 100, intLe(header, 4));
        assertEquals(8000, intLe(header, 24));
        assertEquals(100, intLe(header, 40));
    }

    @Test
    public void durationIsDerivedFromTheSampleFormat() {
        assertEquals(1000L, CmfWavWriter.durationMillis(32000L, 16000, 1, 16));
        assertEquals(500L, CmfWavWriter.durationMillis(8000L, 8000, 1, 16));
        assertEquals(0L, CmfWavWriter.durationMillis(0L, 16000, 1, 16));
    }

    @Test
    public void elapsedTimeIsFormattedForTheWatchScreen() {
        assertEquals("00:00", CmfRecorderStatus.formatElapsed(0L));
        assertEquals("00:00", CmfRecorderStatus.formatElapsed(-5000L));
        assertEquals("00:42", CmfRecorderStatus.formatElapsed(42_000L));
        assertEquals("01:02", CmfRecorderStatus.formatElapsed(62_500L));
        assertEquals("09:59", CmfRecorderStatus.formatElapsed(599_000L));
        assertEquals("1:02:03", CmfRecorderStatus.formatElapsed(3_723_000L));
    }

    @Test
    public void watchTitleCarriesTheStateAndTheTimer() {
        final String recording = CmfRecorderStatus.watchTitle(
                CmfRecorderStatus.State.RECORDING, 42_000L);
        assertTrue(recording, recording.startsWith("REC"));
        assertTrue(recording, recording.endsWith("00:42"));

        final String stopped = CmfRecorderStatus.watchTitle(
                CmfRecorderStatus.State.STOPPED, 0L);
        assertTrue(stopped, stopped.startsWith("STOP"));
        assertTrue(stopped, stopped.endsWith("00:00"));
    }

    @Test
    public void watchTextIsTruncatedOnCharacterBoundaries() {
        // 6 cyrillic characters, two bytes each
        final String cyrillic = "\u043f\u0440\u0438\u0432\u0435\u0442";

        assertEquals("\u043f\u0440", CmfRecorderStatus.truncateUtf8(cyrillic, 5));
        assertEquals(cyrillic, CmfRecorderStatus.truncateUtf8(cyrillic, 12));
        assertEquals("", CmfRecorderStatus.truncateUtf8(null, 10));
        assertEquals("", CmfRecorderStatus.truncateUtf8("abc", 0));
        assertEquals("hello", CmfRecorderStatus.truncateUtf8("hello", 50));
    }

    @Test
    public void watchFieldsNeverOverflowThePayload() {
        final StringBuilder longTitle = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longTitle.append('a');
        }

        final String truncated = CmfRecorderStatus.truncateForWatch(longTitle.toString());
        assertEquals(CmfRecorderStatus.WATCH_TEXT_FIELD_SIZE - 1, truncated.length());
        assertTrue(truncated.getBytes(StandardCharsets.UTF_8).length
                < CmfRecorderStatus.WATCH_TEXT_FIELD_SIZE);
    }

    @Test
    public void fileNamesAreSortableAndPrefixed() {
        final String name = CmfRecorderStatus.fileBaseName(1_757_000_000_000L);
        assertTrue(name, name.startsWith(CmfRecorderStatus.FILE_PREFIX));
        assertEquals(CmfRecorderStatus.FILE_PREFIX.length() + 15, name.length());
    }

    private static String ascii(final byte[] data, final int offset, final int length) {
        return new String(data, offset, length, StandardCharsets.US_ASCII);
    }

    private static int intLe(final byte[] data, final int offset) {
        return (data[offset] & 0xff)
                | ((data[offset + 1] & 0xff) << 8)
                | ((data[offset + 2] & 0xff) << 16)
                | ((data[offset + 3] & 0xff) << 24);
    }

    private static int shortLe(final byte[] data, final int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }
}
