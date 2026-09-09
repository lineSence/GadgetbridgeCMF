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
 * thumbnail used in the watchface carousel. Both are LZ4 compressed RGB565 little endian pixel
 * rows, top row first. Every integer in the file is little endian:</p>
 *
 * <pre>
 * 0x00 u8[4]  file magic 6c 8d c4 a5
 * 0x04 u32    constant 18, not an element count
 * 0x08 u8[8]  zero
 * 0x10 u32    length of the whole full frame block, that is tag and length and payload
 * 0x14        full frame  tag 04 48 47 3a, u32 payload length, LZ4 payload, 466 x 466
 *             thumbnail   tag 04 38 c4 21, u32 payload length, LZ4 payload, 270 x 270
 * end  u8[4]  the file magic again, as a trailer
 * </pre>
 *
 * <p>There is no checksum in the container. Pixels outside the inscribed circle are written as
 * black, the same way the official app does it, because the screen is round.</p>
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

    /** Bytes before the first block: magic, constant, padding and the full frame block length. */
    private static final int CONTAINER_HEADER_SIZE = 0x14;

    /** Block tag plus the payload length in front of every payload. */
    private static final int BLOCK_HEADER_SIZE = 8;

    /** The file magic is repeated at the end of the file. */
    private static final int TRAILER_SIZE = 4;

    /** Fixed value at offset 4. It is not a block count, it is always 18. */
    private static final int CONTAINER_CONSTANT = 18;

    /** The id every photo watchface gets, so the watch never rejects it as a duplicate. */
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
            1    // marker 0x01
            + 4  // file size
            + 4  // watchface id
            + 3  // fixed bytes 01 01 01
            + 2  // clock style
            + 2  // x position
            + 2  // y position
            + 2  // clock colour, RGB565
            + DESCRIPTOR_RESERVED;

    /**
     * Clock styles the firmware offers for photo watchfaces. The clock is always drawn, there is
     * no style that hides it, and the shapes behind the numbers are only known by trying them.
     */
    public static final int[] STYLE_IDS = {0, 1, 2, 3, 4};
    public static final String[] STYLE_LABELS = {
            "Стиль 0",
            "Стиль 1",
            "Стиль 2",
            "Стиль 3",
            "Стиль 4",
    };

    /** Colours the firmware accepts for the clock overlay, as ARGB for the preview. */
    public static final int[] COLOR_ARGB = {
            0xffffffff, 0xff000000, 0xffff3b30, 0xffffcc00, 0xff34c759, 0xff0a84ff,
    };
    public static final String[] COLOR_LABELS = {
            "Белый", "Чёрный", "Красный", "Жёлтый", "Зелёный", "Синий",
    };

    /** Position the official app uses for the clock overlay, and a safe starting point. */
    public static final int DEFAULT_POSITION_X = 56;
    public static final int DEFAULT_POSITION_Y = 77;

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
            this.positionX = DEFAULT_POSITION_X;
            this.positionY = DEFAULT_POSITION_Y;
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

        maskToCircle(fullRaw, FULL_WIDTH, FULL_HEIGHT);
        maskToCircle(thumbRaw, THUMB_WIDTH, THUMB_HEIGHT);

        final byte[] fullPacked = compressChecked(fullRaw, "полный кадр");
        final byte[] thumbPacked = compressChecked(thumbRaw, "миниатюра");

        final int fullBlockSize = BLOCK_HEADER_SIZE + fullPacked.length;
        final int thumbBlockSize = BLOCK_HEADER_SIZE + thumbPacked.length;
        final int totalSize = CONTAINER_HEADER_SIZE + fullBlockSize + thumbBlockSize + TRAILER_SIZE;

        final ByteBuffer buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(MAGIC_FILE);
        buf.putInt(CONTAINER_CONSTANT);
        buf.putLong(0L); // eight reserved zero bytes
        buf.putInt(fullBlockSize);

        buf.put(MAGIC_FULL);
        buf.putInt(fullPacked.length);
        buf.put(fullPacked);

        buf.put(MAGIC_THUMB);
        buf.putInt(thumbPacked.length);
        buf.put(thumbPacked);

        buf.put(MAGIC_FILE);

        if (buf.position() != totalSize) {
            throw new IllegalStateException("Файл занял " + buf.position()
                    + " Б вместо " + totalSize);
        }

        final byte[] file = buf.array();
        final byte[] descriptor = buildDescriptor(params, totalSize);

        final StringBuilder report = new StringBuilder();
        report.append("размер файла=").append(totalSize).append(" Б\n");
        report.append(String.format(Locale.ROOT, "полный кадр=%d Б из %d (%.1f%%)\n",
                fullPacked.length, FULL_RAW_SIZE, 100.0 * fullPacked.length / FULL_RAW_SIZE));
        report.append(String.format(Locale.ROOT, "миниатюра=%d Б из %d (%.1f%%)\n",
                thumbPacked.length, THUMB_RAW_SIZE, 100.0 * thumbPacked.length / THUMB_RAW_SIZE));
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
     * <p>All fields are big endian, like the rest of the CMF command protocol:</p>
     *
     * <pre>
     * 0x00 u8      0x01, start of the descriptor
     * 0x01 u32 BE  total file size
     * 0x05 u32 BE  watchface id, 0xffffffff for a photo watchface
     * 0x09 u8[3]   01 01 01
     * 0x0c u16 BE  clock style id
     * 0x0e u16 BE  clock x position
     * 0x10 u16 BE  clock y position
     * 0x12 u16 BE  clock colour, RGB565
     * 0x14 u8[8]   0xff, reserved
     * </pre>
     *
     * <p>That is {@link #DESCRIPTOR_SIZE} bytes in total. A shorter descriptor is accepted by the
     * transfer but the watch then answers the finish command with code 0x0a, which means the file
     * was stored and never activated. That is exactly what a missing watchface looks like.</p>
     */
    public static byte[] buildDescriptor(final Params params, final int fileSize) {
        final ByteBuffer buf = ByteBuffer.allocate(DESCRIPTOR_SIZE).order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) 0x01);
        buf.putInt(fileSize);
        buf.putInt(PHOTO_WATCHFACE_ID);
        buf.put((byte) 0x01);
        buf.put((byte) 0x01);
        buf.put((byte) 0x01);
        buf.putShort((short) params.styleId);
        buf.putShort((short) params.positionX);
        buf.putShort((short) params.positionY);
        buf.putShort((short) params.getColor565());
        for (int i = 0; i < DESCRIPTOR_RESERVED; i++) {
            buf.put((byte) 0xff);
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
        if (data == null || data.length < CONTAINER_HEADER_SIZE) {
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
     * Dumps the structure of any watchface file as text, so that a file exported by Nothing X and
     * a file built here can be compared on the phone.
     */
    public static String describe(final byte[] data) {
        if (data == null || data.length < 8) {
            return "файл пустой или слишком короткий";
        }

        final StringBuilder out = new StringBuilder();
        out.append("размер=").append(data.length).append(" Б\n");
        out.append("первые 16 Б: ").append(hex(data, 0, Math.min(16, data.length))).append("\n");

        if (!isPhotoWatchface(data)) {
            out.append("это не фотоциферблат: магия не 6c 8d c4 a5\n");
            return describeStructured(data, out);
        }

        final ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(4);
        final int constant = buf.getInt();
        buf.getLong(); // reserved
        final int fullBlockSize = buf.getInt();

        out.append("константа=").append(constant)
                .append(constant == CONTAINER_CONSTANT ? " (верно)" : " (ожидалось 18)").append("\n");
        out.append("блок полного кадра=").append(fullBlockSize).append(" Б\n");

        int offset = CONTAINER_HEADER_SIZE;
        for (int i = 0; i < 2 && offset + BLOCK_HEADER_SIZE <= data.length; i++) {
            final int payloadLength = ByteBuffer
                    .wrap(data, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            out.append("блок ").append(i)
                    .append(": магия=").append(hex(data, offset, 4))
                    .append(" сжатый=").append(payloadLength)
                    .append(" смещение=").append(offset).append("\n");
            if (payloadLength <= 0 || payloadLength > data.length) {
                out.append("длина блока выглядит неверно, разбор остановлен\n");
                break;
            }
            offset += BLOCK_HEADER_SIZE + payloadLength;
        }

        final boolean trailerOk = data.length >= TRAILER_SIZE
                && data[data.length - 4] == MAGIC_FILE[0]
                && data[data.length - 3] == MAGIC_FILE[1]
                && data[data.length - 2] == MAGIC_FILE[2]
                && data[data.length - 1] == MAGIC_FILE[3];
        out.append("хвостовая магия=").append(hex(data, data.length - 4, 4))
                .append(trailerOk ? " (верно)" : " (ожидалось 6c 8d c4 a5)").append("\n");
        out.append("конец блоков=").append(offset)
                .append(offset + TRAILER_SIZE == data.length ? " (сходится)" : " (НЕ сходится)");

        return out.toString();
    }

    /** Reads the header of a watchface exported by the official app. */
    private static String describeStructured(final byte[] data, final StringBuilder out) {
        if (data.length < 0x24) {
            out.append("файл слишком короткий для структурного циферблата");
            return out.toString();
        }

        final ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        final int crcTree = buf.getInt();
        out.append("сигнатура 4..8: ").append(hex(data, 4, 4))
                .append(" (у структурного циферблата 01 00 00 00 или 01 00 00 02)\n");

        final StringBuilder name = new StringBuilder();
        for (int i = 8; i < 8 + 16 && i < data.length; i++) {
            if (data[i] == 0) {
                break;
            }
            name.append((char) (data[i] & 0xff));
        }

        final int sizeA = ByteBuffer.wrap(data, 0x18, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        final int sizeB = ByteBuffer.wrap(data, 0x1c, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();

        out.append(String.format(Locale.ROOT, "crc заголовка=%08x\n", crcTree));
        out.append("имя=").append(name).append("\n");
        out.append("размер без хвоста=").append(sizeA)
                .append(sizeA == data.length - 36 ? " (сходится)" : " (НЕ сходится)").append("\n");
        out.append("размер пула ресурсов=").append(sizeB).append("\n");
        out.append("такой файл грузится с заменой существующего циферблата");

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

    /**
     * Blacks out every pixel outside the inscribed circle. The screen is round, the corners are
     * never shown, and a flat colour there also compresses far better.
     */
    public static void maskToCircle(final byte[] pixels, final int width, final int height) {
        final float centerX = (width - 1) / 2f;
        final float centerY = (height - 1) / 2f;
        final float radius = Math.min(width, height) / 2f;
        final float radiusSquared = radius * radius;

        for (int y = 0; y < height; y++) {
            final float dy = y - centerY;
            for (int x = 0; x < width; x++) {
                final float dx = x - centerX;
                if (dx * dx + dy * dy <= radiusSquared) {
                    continue;
                }
                final int index = (y * width + x) * 2;
                pixels[index] = 0;
                pixels[index + 1] = 0;
            }
        }
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
        for (int i = Math.max(0, offset); i < offset + length && i < data.length; i++) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(String.format(Locale.ROOT, "%02x", data[i]));
        }
        return out.toString();
    }
}
