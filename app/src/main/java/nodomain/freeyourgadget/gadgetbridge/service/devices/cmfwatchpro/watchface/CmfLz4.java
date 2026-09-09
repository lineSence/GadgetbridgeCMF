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

import java.util.Arrays;

/**
 * LZ4 block codec, implemented from the block format specification.
 *
 * <p>The watch stores watchface bitmaps as LZ4 compressed RGB565 data, so building a watchface on
 * the phone needs a compressor. Gadgetbridge has no LZ4 dependency and pulling one in for a single
 * device would be heavy, so this is a compact greedy implementation of the format:</p>
 *
 * <pre>
 * sequence := token literals? offset matchLengthExtension?
 * token    := (literalLength &lt;&lt; 4) | (matchLength - 4)   // 0x0f in a field means "more bytes follow"
 * offset   := u16 little endian, distance back into already decoded output
 * </pre>
 *
 * <p>Format constraints that the decoder on the watch relies on and that this compressor honours:
 * the last five bytes of a block are always literals, no match may start within the last twelve
 * bytes, and the minimum match length is four.</p>
 *
 * <p>{@link #decompress(byte[], int)} exists so the app can verify its own output on the device
 * before anything is sent to the watch. A watchface that fails a round trip here would be rejected
 * by the watch anyway, and the watch is much less forgiving about it.</p>
 */
public final class CmfLz4 {
    /** Shortest sequence the format can encode as a match. */
    private static final int MIN_MATCH = 4;

    /** The tail of a block must be plain literals. */
    private static final int LAST_LITERALS = 5;

    /** No match may start inside this many trailing bytes. */
    private static final int MF_LIMIT = 12;

    private static final int HASH_LOG = 16;
    private static final int HASH_TABLE_SIZE = 1 << HASH_LOG;

    /** The offset field is 16 bit, so a match cannot reach further back than this. */
    private static final int MAX_DISTANCE = 65535;

    private static final int RUN_MASK = 0x0f;
    private static final int ML_MASK = 0x0f;

    /** Knuth multiplicative constant, the same one the reference implementation uses. */
    private static final long HASH_MULTIPLIER = 2654435761L;

    private CmfLz4() {
        // static helper
    }

    /** Worst case output size: incompressible input plus the literal run headers. */
    public static int maxCompressedLength(final int length) {
        return length + length / 255 + 16;
    }

    public static byte[] compress(final byte[] src) {
        final byte[] dest = new byte[maxCompressedLength(src.length)];
        final int size = compress(src, dest);
        return Arrays.copyOf(dest, size);
    }

    /**
     * Compresses {@code src} into {@code dest} and returns the number of bytes written.
     *
     * <p>The search is a single hash table lookup per position, which is fast enough to keep the
     * editor responsive on a 466x466 bitmap while still compressing photos to a fraction of the
     * 434 312 raw bytes.</p>
     */
    public static int compress(final byte[] src, final byte[] dest) {
        int destPos = 0;
        int anchor = 0;

        if (src.length >= MF_LIMIT + MIN_MATCH) {
            final int[] hashTable = new int[HASH_TABLE_SIZE];
            Arrays.fill(hashTable, -1);

            final int mfLimit = src.length - MF_LIMIT;
            final int matchLimit = src.length - LAST_LITERALS;

            int srcPos = 0;
            while (srcPos < mfLimit) {
                final int hash = hash(readInt(src, srcPos));
                final int ref = hashTable[hash];
                hashTable[hash] = srcPos;

                final boolean usable = ref >= 0
                        && srcPos - ref <= MAX_DISTANCE
                        && readInt(src, ref) == readInt(src, srcPos);
                if (!usable) {
                    srcPos++;
                    continue;
                }

                int matchLength = MIN_MATCH;
                while (srcPos + matchLength < matchLimit
                        && src[ref + matchLength] == src[srcPos + matchLength]) {
                    matchLength++;
                }

                destPos = writeSequence(src, anchor, srcPos - anchor, srcPos - ref, matchLength,
                        dest, destPos);

                srcPos += matchLength;
                anchor = srcPos;
            }
        }

        return writeLastLiterals(src, anchor, src.length - anchor, dest, destPos);
    }

    /**
     * Decompresses an LZ4 block of known output size. Used for the self check in the editor.
     *
     * @throws IllegalArgumentException when the block is malformed or does not produce exactly
     *                                  {@code destLength} bytes
     */
    public static byte[] decompress(final byte[] src, final int destLength) {
        final byte[] dest = new byte[destLength];
        int srcPos = 0;
        int destPos = 0;

        while (srcPos < src.length) {
            final int token = src[srcPos++] & 0xff;

            int literalLength = token >>> 4;
            if (literalLength == RUN_MASK) {
                literalLength += readLength(src, srcPos);
                srcPos += lengthSize(src, srcPos);
            }

            if (destPos + literalLength > destLength || srcPos + literalLength > src.length) {
                throw new IllegalArgumentException("Literal run overflows the block");
            }

            System.arraycopy(src, srcPos, dest, destPos, literalLength);
            srcPos += literalLength;
            destPos += literalLength;

            if (srcPos >= src.length) {
                // The block ends with literals, which is what the format requires.
                break;
            }

            if (srcPos + 2 > src.length) {
                throw new IllegalArgumentException("Truncated match offset");
            }

            final int offset = (src[srcPos] & 0xff) | ((src[srcPos + 1] & 0xff) << 8);
            srcPos += 2;

            int matchLength = token & ML_MASK;
            if (matchLength == ML_MASK) {
                matchLength += readLength(src, srcPos);
                srcPos += lengthSize(src, srcPos);
            }
            matchLength += MIN_MATCH;

            if (offset <= 0 || offset > destPos) {
                throw new IllegalArgumentException("Match offset points outside the output");
            }
            if (destPos + matchLength > destLength) {
                throw new IllegalArgumentException("Match overflows the output");
            }

            // Byte by byte on purpose: overlapping matches are legal and are how runs are encoded.
            int ref = destPos - offset;
            for (int i = 0; i < matchLength; i++) {
                dest[destPos++] = dest[ref++];
            }
        }

        if (destPos != destLength) {
            throw new IllegalArgumentException(
                    "Decompressed " + destPos + " bytes, expected " + destLength);
        }

        return dest;
    }

    private static int writeSequence(final byte[] src, final int literalsPos,
                                     final int literalLength, final int offset,
                                     final int matchLength, final byte[] dest, int destPos) {
        final int matchCode = matchLength - MIN_MATCH;

        dest[destPos++] = (byte) ((Math.min(literalLength, RUN_MASK) << 4)
                | Math.min(matchCode, ML_MASK));

        if (literalLength >= RUN_MASK) {
            destPos = writeLength(literalLength - RUN_MASK, dest, destPos);
        }

        System.arraycopy(src, literalsPos, dest, destPos, literalLength);
        destPos += literalLength;

        dest[destPos++] = (byte) (offset & 0xff);
        dest[destPos++] = (byte) ((offset >>> 8) & 0xff);

        if (matchCode >= ML_MASK) {
            destPos = writeLength(matchCode - ML_MASK, dest, destPos);
        }

        return destPos;
    }

    private static int writeLastLiterals(final byte[] src, final int literalsPos,
                                         final int literalLength, final byte[] dest, int destPos) {
        dest[destPos++] = (byte) (Math.min(literalLength, RUN_MASK) << 4);

        if (literalLength >= RUN_MASK) {
            destPos = writeLength(literalLength - RUN_MASK, dest, destPos);
        }

        System.arraycopy(src, literalsPos, dest, destPos, literalLength);
        return destPos + literalLength;
    }

    /** Lengths above the 4 bit field are encoded as a run of 0xff bytes plus a remainder. */
    private static int writeLength(int length, final byte[] dest, int destPos) {
        while (length >= 255) {
            dest[destPos++] = (byte) 255;
            length -= 255;
        }
        dest[destPos++] = (byte) length;
        return destPos;
    }

    private static int readLength(final byte[] src, int srcPos) {
        int length = 0;
        int current;
        do {
            if (srcPos >= src.length) {
                throw new IllegalArgumentException("Truncated length extension");
            }
            current = src[srcPos++] & 0xff;
            length += current;
        } while (current == 255);
        return length;
    }

    private static int lengthSize(final byte[] src, int srcPos) {
        int size = 0;
        int current;
        do {
            current = src[srcPos + size] & 0xff;
            size++;
        } while (current == 255);
        return size;
    }

    private static int readInt(final byte[] src, final int pos) {
        return (src[pos] & 0xff)
                | ((src[pos + 1] & 0xff) << 8)
                | ((src[pos + 2] & 0xff) << 16)
                | ((src[pos + 3] & 0xff) << 24);
    }

    private static int hash(final int value) {
        return (int) (((value & 0xffffffffL) * HASH_MULTIPLIER >>> 16) & (HASH_TABLE_SIZE - 1));
    }
}
