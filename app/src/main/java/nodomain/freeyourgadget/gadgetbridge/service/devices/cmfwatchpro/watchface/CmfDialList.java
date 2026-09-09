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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads and writes the list of watchfaces installed on the watch.
 *
 * <p>The watch keeps a fixed number of dial slots, and the official app never uploads a watchface
 * without first making room for it. There is no separate "delete" or "set active" command: the
 * phone reads the whole list, then writes the whole list back. Writing it back with one id missing
 * deletes that watchface, and writing it back with an id first makes that watchface active.</p>
 *
 * <pre>
 * query   9055: type 0x00
 * reply   a055: result u8, activeIndex u8, total u8, max u8, total x dialId u32 LE, ffffffff
 * rewrite 9055: type 0x01, N x dialId u32 LE, ffffffff
 * </pre>
 *
 * <p>Ids are little endian inside the payload, unlike the command header. Stock watchfaces use ids
 * 273 to 376; a photo watchface built on the phone always uses {@link #PHOTO_DIAL_ID}.</p>
 */
public final class CmfDialList {
    /** Asks the watch for the current list. */
    public static final byte TYPE_QUERY = 0x00;

    /** Writes a list back, which is how a dial is deleted or made active. */
    public static final byte TYPE_ORDER = 0x01;

    /** Closes the id list in both directions. */
    public static final int TERMINATOR = 0xffffffff;

    /** The id every photo watchface gets, so the watch never treats it as a duplicate. */
    public static final int PHOTO_DIAL_ID = 0xffffffff;

    private static final int HEADER_SIZE = 4;

    private CmfDialList() {
        // static helper
    }

    /** One snapshot of the watch's dial list. */
    public static final class Dials {
        public final int result;
        public final int activeIndex;
        public final int total;
        public final int max;
        public final List<Integer> ids;
        public final String raw;

        Dials(final int result, final int activeIndex, final int total, final int max,
              final List<Integer> ids, final String raw) {
            this.result = result;
            this.activeIndex = activeIndex;
            this.total = total;
            this.max = max;
            this.ids = Collections.unmodifiableList(ids);
            this.raw = raw;
        }

        /** True when no further watchface fits without deleting one first. */
        public boolean isFull() {
            return max > 0 && ids.size() >= max;
        }

        public boolean isEmpty() {
            return ids.isEmpty();
        }
    }

    public static byte[] buildQuery() {
        return new byte[]{TYPE_QUERY};
    }

    /**
     * Builds the payload that writes {@code ids} back to the watch. The list is authoritative:
     * whatever is missing from it is removed from the watch, and whatever comes first becomes the
     * active watchface.
     */
    public static byte[] buildOrder(final List<Integer> ids) {
        final ByteBuffer buf = ByteBuffer.allocate(1 + ids.size() * 4 + 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        buf.put(TYPE_ORDER);
        for (final Integer id : ids) {
            buf.putInt(id);
        }
        buf.putInt(TERMINATOR);
        return buf.array();
    }

    /** Copy of {@code ids} without {@code id}, in the original order. */
    public static List<Integer> withoutId(final List<Integer> ids, final int id) {
        final List<Integer> out = new ArrayList<>(ids.size());
        for (final Integer candidate : ids) {
            if (candidate != id) {
                out.add(candidate);
            }
        }
        return out;
    }

    /** Copy of {@code ids} with {@code id} moved to the front, which activates it. */
    public static List<Integer> activeFirst(final List<Integer> ids, final int id) {
        final List<Integer> out = new ArrayList<>(ids.size());
        out.add(id);
        for (final Integer candidate : ids) {
            if (candidate != id) {
                out.add(candidate);
            }
        }
        return out;
    }

    /**
     * Parses a list reply. A short or damaged payload never throws: the raw bytes are kept in
     * {@link Dials#raw} so that the phone screen can show them even when the layout is wrong.
     */
    public static Dials parse(final byte[] payload) {
        final String raw = hex(payload);

        if (payload == null || payload.length < HEADER_SIZE) {
            return new Dials(-1, -1, 0, 0, new ArrayList<>(), raw);
        }

        final int result = payload[0] & 0xff;
        final int activeIndex = payload[1] & 0xff;
        final int total = payload[2] & 0xff;
        final int max = payload[3] & 0xff;

        final ByteBuffer buf = ByteBuffer
                .wrap(payload, HEADER_SIZE, payload.length - HEADER_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN);

        // The count comes from the header, because the terminator is the same value as the id of
        // a photo watchface and cannot be told apart from it by reading the bytes alone.
        final int count = Math.min(total, buf.remaining() / 4);
        final List<Integer> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(buf.getInt());
        }

        return new Dials(result, activeIndex, total, max, ids, raw);
    }

    /** Human readable name of a stock watchface, or null when the id is not a stock one. */
    public static String nameFor(final int id) {
        return CATALOG.get(id);
    }

    /** Line shown in the watchface list on the phone. */
    public static String label(final int id) {
        if (id == PHOTO_DIAL_ID) {
            return "Фотоциферблат (свой)";
        }

        final String name = nameFor(id);
        if (name != null) {
            return id + " — " + name;
        }

        return String.format(Locale.ROOT, "id %d (0x%08x)", id, id);
    }

    public static String hex(final byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }

        final StringBuilder out = new StringBuilder(data.length * 3);
        for (final byte b : data) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(String.format(Locale.ROOT, "%02x", b));
        }
        return out.toString();
    }

    /**
     * Names of the stock watchfaces, as the official app shows them. Only used to make the list on
     * the phone readable; an unknown id is shown as a number and still works.
     */
    private static final String CATALOG_SOURCE =
            "273:Activity Mood;274:Sun Circle;275:SlopeTime;276:Dichotomy;277:Prismatic Time;"
            + "280:Multifunction;281:Metaball;282:Radar Sweep;283:Radio;284:Square;285:Widgets;"
            + "286:Sundial;287:Simple Dial;288:Type;289:Rotate;290:Gradual;291:Vertical;292:City;"
            + "293:Stairs;294:Sudoku;295:Vortex;296:Ladder;297:Ray;298:Eclectic;299:Echo;"
            + "300:Mono Dial;301:Orbit;302:Calendar;303:Space;304:Elaborate 2;305:Dots;"
            + "306:Large Number;307:Sprung;308:Sundial 2;309:Gradient;310:Glare;311:Bold;"
            + "312:Disc;313:Classical;314:Fragment;315:Infinite;316:Trailing;317:Disc 2;"
            + "318:Dominos;319:One Line;320:Orienteer;321:Revolution;322:Glare 2;323:Dash;"
            + "324:Finesse;325:Metric;326:Chrono Master;327:Digit Max;328:Coherent;329:Wheel;"
            + "330:Zenith;331:Intersection;332:Flux;333:Circularity;334:Globe of Time;"
            + "335:Time Phase;336:Energetic;337:Time Finder;338:Chronos;339:Suprematism;"
            + "340:Sport Mode;341:Dual View;342:Perfect Match;343:Progress Day;344:InfoMeter;"
            + "345:Time Dot;346:Time Windmill;347:Large Panel;348:Tumbler;349:Theatre;"
            + "350:Timeline;351:Cyclopes;352:Elegant Sweep;353:Dual phase;354:Dual;"
            + "357:Silhouette;358:Asteroid;359:Ring data;360:Explorer;361:TempoG;362:Steady;"
            + "363:Vintage;364:SportPulse;365:Elegance;366:Combo;367:Complex Figure;"
            + "368:Function;369:Solar System;370:Hemisphere;371:ActiveTrio;372:Time Wheel;"
            + "373:Traditional Pointer;374:Cirquary;375:InfoHub;376:Digits time";

    private static final Map<Integer, String> CATALOG = buildCatalog();

    private static Map<Integer, String> buildCatalog() {
        final Map<Integer, String> out = new HashMap<>();
        for (final String entry : CATALOG_SOURCE.split(";")) {
            final int separator = entry.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            try {
                out.put(Integer.parseInt(entry.substring(0, separator)), entry.substring(separator + 1));
            } catch (final NumberFormatException ignored) {
                // A broken entry only costs a readable name, so it is not worth failing over.
            }
        }
        return Collections.unmodifiableMap(out);
    }
}
