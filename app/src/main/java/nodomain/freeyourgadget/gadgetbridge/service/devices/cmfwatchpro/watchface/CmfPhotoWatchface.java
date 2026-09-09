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
package nodomain.freeyourgadget.gadgetbridge.service.devices.cmfwatchpro.watchface;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/**
 * Builds a photo watchface for the CMF Watch Pro 2 out of a bitmap chosen on the phone.
 *
 * <h2>File layout</h2>
 *
 * <p>A photo watchface holds two bitmaps: the watchface itself at the full screen resolution and a
 * thumbnail used in the watchface carousel. Both are stored as LZ4 compressed RGB565 little endian
 * pixel rows, top row first, and each one is tagged with a fixed magic:</p>
 *
 * <pre>
 * 6c 8d c4 a5   file magic
 * 04 48 47 3a   full bitmap,  466 x 466, 434312 raw bytes
 * 04 38 c4 21   thumbnail,    270 x 270, 145800 raw bytes
 * </pre>
 *
 * <p>The container around those payloads is a block table, which is the part reconstructed from
 * reverse engineering rather than from documentation. It is deliberately kept in one place here so
 * that a single constant can be corrected once a watchface exported by Nothing X has been compared
 * against it with {@link #describe(byte[])}:</p>
 *
 * <pre>
 * 0x00 u8[4]  file magic
 * 0x04 u32 LE total file size including the trailing checksum
 * 0x08 u32 LE block count, always 2
 * 0x0c u32 LE flags, 0
 * 0x10 block table, 16 bytes per block:
 *        u8[4]  block magic
 *        u32 LE raw size
 *        u32 LE compressed size
 *        u32 LE payload offset from the start of the file
 *      payloads in table order
 *      u32 LE CRC32 of everything before it
 * </pre>
 *
 * <p>The CRC uses the same raw variant as the rest of the CMF protocol: polynomial 0xedb88320,
 * initial value zero and no final inversion.</p>
 *
 * <h2>Clock overlay</h2>
 *
 * <p>The watch draws the clock itself on top of the photo, so the file carries no text. Style,
 * position and colour travel in the transfer descriptor built by {@link #buildDescriptor(Params,
 * int)} and are applied by the firmware.</p>
 */
public final class CmfPhotoWatchface {
    public static final int FULL_WIDTH = 466;
    public static final int FULL_HEIGHT = 466;
    public static final int THUMB_WIDTH = 270;
    public static final int THUMB_HEIGHT = 270;

    /** Rows below this are hidden behind the bezel on the Watch Pro 2. */
    public static final int VISIBLE_HEIGHT = 360;

    public static final int FULL_RAW_SIZE = FULL_WIDTH * FULL_HEIGHT * 2;
    public static final int THUMB_RAW_SIZE = THUMB_WIDTH * THUMB_HEIGHT * 2;

    private static final byte[] MAGIC_FILE = {0x6c, (byte) 0x8d, (byte) 0xc4, (byte) 0xa5};
    private static final byte[] MAGIC_FULL = {0x04, 0x48, 0x47, 0x3a};
    private static final byte[] MAGIC_THUMB = {0x04, 0x38, (byte) 0xc4, 0x21};

    private static final int HEADER_SIZE = 16;
    private static final int BLOCK_ENTRY_SIZE = 16;
    private static final int BLOCK_COUNT = 2;
    private static final int CHECKSUM_SIZE = 4;

    /** The watch ignores the numeric id for photo watchfaces and always replaces the custom slot. */
    public static final int PHOTO_WATCHFACE_ID = 0xffffffff;

    /** Number of trailing 0xff bytes in the transfer descriptor. */
    private static final int DESCRIPTOR_RESERVED = 8;

    /**
     * Length of the transfer descriptor in bytes.
     *
     * <p>Derived from the field list in {@link #buildDescriptor(Params, int)} rather than written
     * as a literal, because getting it wrong costs a {@link java.nio.BufferOverflowException},
     * which carries no message at all and therefore reports itself as a null error.</p>
     */
    public static final int DESCRIPTOR_SIZE =
            1    // marker 0xa5
            + 4  // file size
            + 4  // watchface id
            + 3  // fixed bytes 01 01 01
            + 1  // clock style
            + 2  // x position
            + 2  // y position
            + 2  // clock colour, RGB565
            + DESCRIPTOR_RESERVED;

    /** Clock styles the firmware offers for photo watchfaces. */
    public static final int[] STYLE_IDS = {0, 1, 2, 3};
    public static final String[] STYLE_LABELS = {
            "Крупные цифры",
            "Тонкие цифры",
            "Цифры и дата",
            "Компактный блок",
    };

    /** Colours the firmware accepts for the clock overlay, as ARGB for the preview. */
    public static final int[] COLOR_ARGB = {
            0xffffffff, 0xff000000, 0xffff3b30, 0xffffcc00, 0xff34c759, 0xff0a84ff,
    };
    public static final String[] COLOR_LABELS = {
            "Белый", "Чёрный", "Красный", "Жёлтый", "Зелёный", "Синий",
    };

    private CmfPhotoWatchface() {
        // static helper
    }

    /** Clock overlay settings chosen in the editor. */
    public static class Params {
        public int styleId;
        public int positionX;
        public int positionY;
        public int colorArgb;

        public Params() {
            this.styleId = STYLE_IDS[0];
            this.positionX = FULL_WIDTH / 2;
            this.positionY = VISIBLE_HEIGHT / 2;
            this.colorArgb = COLOR_ARGB[0];
        }

        public int getColor565() {
            return toRgb565(colorArgb);
        }
    }

    /** Everything the editor needs after a build: the file, its descriptor and a human report. */
    public static class Result {
        public final byte[] file;
        public final byte[] descriptor;
        public final int fullCompressedSize;
        public final int thumbCompressedSize;
        public final String report;

        Result(final byte[] file, final byte[] descriptor, final int fullCompressedSize,
               final int thumbCompressedSize, final String report) {
            this.file = file;
            this.descriptor = descriptor;
            this.fullCompressedSize = fullCompressedSize;
            this.thumbCompressedSize = thumbCompressedSize;
            this.report = report;
        }
    }

    /**
     * Builds a complete photo watchface file from {@code source}.
     *
     * <p>The bitmap is centre cropped to a square and scaled twice, once for the screen and once
     * for the carousel thumbnail. Both compressed blocks are decompressed again and compared to the
     * input, so a corrupt payload is caught on the phone instead of on the watch.</p>
     *
     * @throws IllegalArgumentException when the bitmap cannot be used
     * @throws IllegalStateException    when the LZ4 round trip fails
     */
    public static Result build(final Bitmap source, final Params params) {
        if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) {
            throw new IllegalArgumentException("Пустое изображение");
        }

        final Bitmap full = scaleCenterCrop(source, FULL_WIDTH, FULL_HEIGHT);
        final Bitmap thumb = scaleCenterCrop(source, THUMB_WIDTH, THUMB_HEIGHT);

        final byte[] fullRaw = toRgb565Bytes(full);
        final byte[] thumbRaw = toRgb565Bytes(thumb);

        if (full != source) {
            full.recycle();
        }
        if (thumb != source) {
            thumb.recycle();
        }

        final byte[] fullPacked = compressChecked(fullRaw, "полный кадр");
        final byte[] thumbPacked = compressChecked(thumbRaw, "миниатюра");

        final int payloadOffset = HEADER_SIZE + BLOCK_COUNT * BLOCK_ENTRY_SIZE;
        final int totalSize = payloadOffset + fullPacked.length + thumbPacked.length + CHECKSUM_SIZE;

        final ByteBuffer buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(MAGIC_FILE);
        buf.putInt(totalSize);
        buf.putInt(BLOCK_COUNT);
        buf.putInt(0); // flags

        buf.put(MAGIC_FULL);
        buf.putInt(FULL_RAW_SIZE);
        buf.putInt(fullPacked.length);
        buf.putInt(payloadOffset);

        buf.put(MAGIC_THUMB);
        buf.putInt(THUMB_RAW_SIZE);
        buf.putInt(thumbPacked.length);
        buf.putInt(payloadOffset + fullPacked.length);

        buf.put(fullPacked);
        buf.put(thumbPacked);

        final byte[] file = buf.array();
        final int checksum = crc32Raw(file, 0, totalSize - CHECKSUM_SIZE);
        buf.putInt(checksum);

        final byte[] descriptor = buildDescriptor(params, totalSize);

        final StringBuilder report = new StringBuilder();
        report.append("размер файла=").append(totalSize).append(" Б\n");
        report.append(String.format(Locale.ROOT, "полный кадр=%d Б из %d (%.1f%%)\n",
                fullPacked.length, FULL_RAW_SIZE, 100.0 * fullPacked.length / FULL_RAW_SIZE));
        report.append(String.format(Locale.ROOT, "миниатюра=%d Б из %d (%.1f%%)\n",
                thumbPacked.length, THUMB_RAW_SIZE, 100.0 * thumbPacked.length / THUMB_RAW_SIZE));
        report.append(String.format(Locale.ROOT, "crc32=%08x\n", checksum));
        report.append("стиль=").append(params.styleId)
                .append(" позиция=").append(params.positionX).append(",").append(params.positionY)
                .append(String.format(Locale.ROOT, " цвет565=%04x\n", params.getColor565()));
        report.append("дескриптор=").append(hex(descriptor, 0, descriptor.length)).append("\n");
        report.append("самопроверка LZ4=пройдена");

        return new Result(file, descriptor, fullPacked.length, thumbPacked.length,
                report.toString());
    }

    /**
     * Builds the payload of the second transfer init request for a photo watchface.
     *
     * <p>Structured watchfaces send a random watchface id here. Photo watchfaces instead pin the id
     * to {@link #PHOTO_WATCHFACE_ID} and append the clock overlay descriptor, which is how the
     * firmware learns where to draw the time. All fields are big endian, like the rest of the CMF
     * command protocol:</p>
     *
     * <pre>
     * 0x00 u8      0xa5, the same marker the structured path sends
     * 0x01 u32 BE  total file size
     * 0x05 u32 BE  watchface id, 0xffffffff for the photo slot
     * 0x09 u8[3]   01 01 01, photo marker, asset count, overlay enabled
     * 0x0c u8      clock style id
     * 0x0d u16 BE  clock x position
     * 0x0f u16 BE  clock y position
     * 0x11 u16 BE  clock colour, RGB565
     * 0x13 u8[8]   0xff, reserved
     * </pre>
     *
     * <p>That is {@link #DESCRIPTOR_SIZE} bytes in total.</p>
     */
    public static byte[] buildDescriptor(final Params params, final int fileSize) {
        final ByteBuffer buf = ByteBuffer.allocate(DESCRIPTOR_SIZE).order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) 0xa5);
        buf.putInt(fileSize);
        buf.putInt(PHOTO_WATCHFACE_ID);
        buf.put((byte) 0x01); // photo watchface marker
        buf.put((byte) 0x01); // asset count in the container that the firmware installs
        buf.put((byte) 0x01); // overlay enabled
        buf.put((byte) params.styleId);
        buf.putShort((short) params.positionX);
        buf.putShort((short) params.positionY);
        buf.putShort((short) params.getColor565());
        for (int i = 0; i < DESCRIPTOR_RESERVED; i++) {
            buf.put((byte) 0xff); // reserved, the stock app sends 0xff here
        }

        if (buf.position() != DESCRIPTOR_SIZE) {
            // Cannot happen while DESCRIPTOR_SIZE is derived from the same field list, but a
            // named failure is worth more than a silent truncation if somebody edits one side.
            throw new IllegalStateException("Дескриптор занял " + buf.position()
                    + " Б вместо " + DESCRIPTOR_SIZE);
        }

        return buf.array();
    }

    /** True when {@code data} starts with the photo watchface magic. */
    public static boolean isPhotoWatchface(final byte[] data) {
        if (data == null || data.length < HEADER_SIZE) {
            return false;
        }
        for (int i = 0; i < MAGIC_FILE.length; i++) {
            if (data[i] != MAGIC_FILE[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Dumps the structure of any watchface file as text.
     *
     * <p>This is the tool for fixing the container layout: export a watchface from Nothing X, open
     * it in the editor and compare the dump with the layout documented above.</p>
     */
    public static String describe(final byte[] data) {
        if (data == null || data.length < 8) {
            return "файл пустой или слишком короткий";
        }

        final StringBuilder out = new StringBuilder();
        out.append("размер=").append(data.length).append(" Б\n");
        out.append("первые 16 Б: ").append(hex(data, 0, Math.min(16, data.length))).append("\n");

        if (!isPhotoWatchface(data)) {
            out.append("магия файла не 6c 8d c4 a5\n");
            out.append("это не фото-циферблат, возможно структурный циферблат\n");
            if (data.length > 40) {
                out.append("байты 4..8: ").append(hex(data, 4, 4))
                        .append(" (структурный циферблат даёт 01 00 00 02)\n");
            }
            return out.toString();
        }

        final ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(4);
        final int declaredSize = buf.getInt();
        final int blockCount = buf.getInt();
        final int flags = buf.getInt();

        out.append("заявленный размер=").append(declaredSize)
                .append(declaredSize == data.length ? " (совпадает)" : " (НЕ совпадает)").append("\n");
        out.append("блоков=").append(blockCount).append(" флаги=").append(flags).append("\n");

        for (int i = 0; i < blockCount && buf.remaining() >= BLOCK_ENTRY_SIZE; i++) {
            final byte[] magic = new byte[4];
            buf.get(magic);
            final int rawSize = buf.getInt();
            final int packedSize = buf.getInt();
            final int offset = buf.getInt();
            out.append("блок ").append(i).append(": магия=").append(hex(magic, 0, 4))
                    .append(" сырой=").append(rawSize)
                    .append(" сжатый=").append(packedSize)
                    .append(" смещение=").append(offset).append("\n");
        }

        if (data.length >= CHECKSUM_SIZE) {
            final int stored = ByteBuffer.wrap(data, data.length - CHECKSUM_SIZE, CHECKSUM_SIZE)
                    .order(ByteOrder.LITTLE_ENDIAN).getInt();
            final int computed = crc32Raw(data, 0, data.length - CHECKSUM_SIZE);
            out.append(String.format(Locale.ROOT, "crc32 в файле=%08x рассчитан=%08x %s",
                    stored, computed, stored == computed ? "(совпадает)" : "(НЕ совпадает)"));
        }

        return out.toString();
    }

    /** Centre crops to the target aspect ratio and scales to the target size. */
    public static Bitmap scaleCenterCrop(final Bitmap source, final int width, final int height) {
        final int sourceWidth = source.getWidth();
        final int sourceHeight = source.getHeight();

        final float scale = Math.max((float) width / sourceWidth, (float) height / sourceHeight);
        final int scaledWidth = Math.round(sourceWidth * scale);
        final int scaledHeight = Math.round(sourceHeight * scale);
        final int left = (scaledWidth - width) / 2;
        final int top = (scaledHeight - height) / 2;

        final Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(out);
        final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        canvas.drawBitmap(
                source,
                new Rect(0, 0, sourceWidth, sourceHeight),
                new Rect(-left, -top, scaledWidth - left, scaledHeight - top),
                paint
        );
        return out;
    }

    /** Converts a bitmap to RGB565 little endian, top row first. */
    public static byte[] toRgb565Bytes(final Bitmap bitmap) {
        final int width = bitmap.getWidth();
        final int height = bitmap.getHeight();
        final int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        final byte[] out = new byte[width * height * 2];
        for (int i = 0; i < pixels.length; i++) {
            final int rgb565 = toRgb565(pixels[i]);
            out[i * 2] = (byte) (rgb565 & 0xff);
            out[i * 2 + 1] = (byte) ((rgb565 >>> 8) & 0xff);
        }
        return out;
    }

    public static int toRgb565(final int argb) {
        final int r = (argb >>> 16) & 0xff;
        final int g = (argb >>> 8) & 0xff;
        final int b = argb & 0xff;
        return ((r & 0xf8) << 8) | ((g & 0xfc) << 3) | (b >>> 3);
    }

    /**
     * CRC32 as the CMF protocol uses it: reflected polynomial 0xedb88320, initial value zero, no
     * final inversion. This is not {@link java.util.zip.CRC32}, which starts at 0xffffffff.
     */
    public static int crc32Raw(final byte[] data, final int offset, final int length) {
        int crc = 0;
        for (int i = offset; i < offset + length; i++) {
            crc ^= data[i] & 0xff;
            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 1) != 0) {
                    crc = (crc >>> 1) ^ 0xedb88320;
                } else {
                    crc >>>= 1;
                }
            }
        }
        return crc;
    }

    private static byte[] compressChecked(final byte[] raw, final String what) {
        final byte[] packed = CmfLz4.compress(raw);

        final byte[] roundTrip;
        try {
            roundTrip = CmfLz4.decompress(packed, raw.length);
        } catch (final RuntimeException e) {
            throw new IllegalStateException("LZ4 не распаковался обратно: " + what, e);
        }

        for (int i = 0; i < raw.length; i++) {
            if (raw[i] != roundTrip[i]) {
                throw new IllegalStateException(
                        "LZ4 испортил данные: " + what + ", байт " + i);
            }
        }

        return packed;
    }

    private static String hex(final byte[] data, final int offset, final int length) {
        final StringBuilder out = new StringBuilder(length * 3);
        for (int i = offset; i < offset + length && i < data.length; i++) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(String.format(Locale.ROOT, "%02x", data[i]));
        }
        return out.toString();
    }
}
