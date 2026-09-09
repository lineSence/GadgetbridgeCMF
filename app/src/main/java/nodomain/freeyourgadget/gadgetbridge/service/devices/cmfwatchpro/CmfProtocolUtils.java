/* Copyright (C) 2026 lineSence

   This file is part of Gadgetbridge.

   Gadgetbridge is free software: you can redistribute it and/or modify
   it under the terms of the GNU Affero General Public License as published
   by the Free Software Foundation, either version 3 of the License, or
   (at your option) any later version.

   Gadgetbridge is distributed in the hope that it will be useful,
   but WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
   GNU Affero General Public License for more details.
*/
package nodomain.freeyourgadget.gadgetbridge.service.devices.cmfwatchpro;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.model.Alarm;
import nodomain.freeyourgadget.gadgetbridge.model.Contact;
import nodomain.freeyourgadget.gadgetbridge.util.StringUtils;

/** Small, deterministic CMF payload builders shared by the transport and unit tests. */
public final class CmfProtocolUtils {
    public static final int MAX_CONTACTS = 20;
    public static final int CONTACT_RECORD_SIZE = 57;

    public static final int MAX_ALARMS = 5;
    public static final int ALARM_RECORD_SIZE = 40;
    /** The watch only stores 8 label bytes, at the end of the record. */
    public static final int ALARM_LABEL_SIZE = 8;
    public static final int ALARM_LABEL_OFFSET = 32;

    public static final int MAX_STEPS_GOAL = 1_000_000;
    public static final int MAX_DISTANCE_GOAL_METERS = 1_000_000;
    public static final int MAX_CALORIES_GOAL_KCAL = 0xffff;

    /** Tolerance used when deciding whether a time sync would move the watch clock backwards. */
    public static final int TIME_SYNC_TOLERANCE_SECONDS = 120;

    private CmfProtocolUtils() {
    }

    /**
     * CMF Watch Pro 2 DailyTargetBean v1:
     * steps(u32 BE) | distance_m(u32 BE) | calories_kcal(u16 BE).
     * <p>
     * Values the protocol cannot represent are clamped instead of rejected: this payload is
     * built while the device is still initializing, so throwing here aborts initialization
     * and leaves the device stuck in AUTHENTICATING.
     */
    @NonNull
    public static byte[] buildGoalsPayload(final int steps, final int distanceMeters, final int caloriesKcal) {
        final int safeSteps = clamp(steps, 0, MAX_STEPS_GOAL);
        final int safeDistance = clamp(distanceMeters, 0, MAX_DISTANCE_GOAL_METERS);
        final int safeCalories = clamp(caloriesKcal, 0, MAX_CALORIES_GOAL_KCAL);

        final ByteBuffer buf = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(safeSteps);
        buf.putInt(safeDistance);
        buf.putShort((short) safeCalories);
        return buf.array();
    }

    @NonNull
    public static byte[] buildContactsPayload(final List<? extends Contact> contacts) {
        final int count = Math.min(contacts.size(), MAX_CONTACTS);
        final ByteBuffer buf = ByteBuffer.allocate(CONTACT_RECORD_SIZE * count);

        for (int i = 0; i < count; i++) {
            final Contact contact = contacts.get(i);
            final byte[] name = StringUtils.truncateToBytes(contact.getName(), 32);
            final byte[] phone = StringUtils.truncateToBytes(contact.getNumber(), 25);

            buf.put(name);
            buf.put(new byte[32 - name.length]);
            buf.put(phone);
            buf.put(new byte[25 - phone.length]);
        }

        return buf.array();
    }

    /**
     * Builds the 40-byte alarm records the watch expects, byte-for-byte identical to the
     * encoding the transport used inline:
     * <p>
     * seconds_since_midnight(u32 BE) | index(u8) | enabled(u8) | repetition(u8) | 0xff
     * | 24 reserved zero bytes | label(8 bytes, right aligned at byte 32)
     * <p>
     * The index is the sequential slot number in the payload, which is the behaviour that has
     * been observed to work with the watch. Unused alarms are skipped and the payload is
     * capped at {@link #MAX_ALARMS} records.
     */
    @NonNull
    public static byte[] buildAlarmsPayload(final List<? extends Alarm> alarms) {
        final List<Alarm> usable = new ArrayList<>();
        for (final Alarm alarm : alarms) {
            if (alarm == null || alarm.getUnused()) {
                continue;
            }
            usable.add(alarm);
            if (usable.size() == MAX_ALARMS) {
                break;
            }
        }

        final ByteBuffer buf = ByteBuffer.allocate(ALARM_RECORD_SIZE * usable.size()).order(ByteOrder.BIG_ENDIAN);
        int index = 0;
        for (final Alarm alarm : usable) {
            buf.putInt(alarm.getHour() * 3600 + alarm.getMinute() * 60);
            buf.put((byte) index++);
            buf.put((byte) (alarm.getEnabled() ? 0x01 : 0x00));
            buf.put((byte) alarm.getRepetition());
            buf.put((byte) 0xff);
            buf.put(new byte[ALARM_LABEL_OFFSET - 8]);

            final byte[] label = StringUtils.truncateToBytes(alarm.getTitle(), ALARM_LABEL_SIZE);
            buf.put(new byte[ALARM_LABEL_SIZE - label.length]);
            buf.put(label);
        }

        return buf.array();
    }

    /**
     * Returns false only when sending the current time would move the watch clock materially
     * backwards, which the watch handles badly. A small tolerance avoids false positives from
     * normal wall-clock adjustments. This is not a de-duplication guard: repeated syncs with a
     * monotonically increasing clock are always allowed.
     */
    public static boolean shouldSendTime(final long nowEpochSeconds, final long lastSentEpochSeconds) {
        if (lastSentEpochSeconds <= 0) {
            return true;
        }
        return nowEpochSeconds + TIME_SYNC_TOLERANCE_SECONDS >= lastSentEpochSeconds;
    }

    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(max, value));
    }
}
