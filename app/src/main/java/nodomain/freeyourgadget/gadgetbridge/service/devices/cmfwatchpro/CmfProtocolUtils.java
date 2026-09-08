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
    public static final int ALARM_RECORD_SIZE = 40;
    public static final int ALARM_LABEL_SIZE = 32;

    private CmfProtocolUtils() {
    }

    /**
     * CMF Watch Pro 2 DailyTargetBean v1:
     * steps(u32 BE) | distance_m(u32 BE) | calories_kcal(u16 BE).
     */
    @NonNull
    public static byte[] buildGoalsPayload(final int steps, final int distanceMeters, final int caloriesKcal) {
        if (steps < 0 || distanceMeters < 0 || caloriesKcal < 0
                || caloriesKcal > 0xffff) {
            throw new IllegalArgumentException("CMF goal value is out of range");
        }

        final ByteBuffer buf = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(steps);
        buf.putInt(distanceMeters);
        buf.putShort((short) caloriesKcal);
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
     * Builds the full 40-byte alarm record used by the watch. Labels start at byte 8
     * and occupy 32 bytes. Alarm position is kept stable instead of being compacted.
     */
    @NonNull
    public static byte[] buildAlarmsPayload(final List<? extends Alarm> alarms) {
        final List<Alarm> usable = new ArrayList<>();
        for (final Alarm alarm : alarms) {
            if (!alarm.getUnused()) {
                usable.add(alarm);
            }
        }

        final ByteBuffer buf = ByteBuffer.allocate(ALARM_RECORD_SIZE * usable.size()).order(ByteOrder.BIG_ENDIAN);
        for (final Alarm alarm : usable) {
            buf.putInt(alarm.getHour() * 3600 + alarm.getMinute() * 60);
            buf.put((byte) alarm.getPosition());
            buf.put((byte) (alarm.getEnabled() ? 0x01 : 0x00));
            buf.put((byte) alarm.getRepetition());
            buf.put((byte) 0x00);

            final byte[] label = StringUtils.truncateToBytes(alarm.getTitle(), ALARM_LABEL_SIZE);
            buf.put(label);
            buf.put(new byte[ALARM_LABEL_SIZE - label.length]);
        }

        return buf.array();
    }

    /**
     * Avoid sending a time that is materially earlier than the last time sent to the watch.
     * A small tolerance avoids false positives from normal wall-clock adjustments.
     */
    public static boolean shouldSendTime(final long nowEpochSeconds, final long lastSentEpochSeconds) {
        if (lastSentEpochSeconds <= 0) {
            return true;
        }
        return nowEpochSeconds + 120 >= lastSentEpochSeconds;
    }
}
