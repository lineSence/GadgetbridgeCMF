package nodomain.freeyourgadget.gadgetbridge.service.devices.cmfwatchpro;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import nodomain.freeyourgadget.gadgetbridge.model.Alarm;
import nodomain.freeyourgadget.gadgetbridge.model.Contact;

public class CmfProtocolUtilsTest {
    @Test
    public void goalsPayloadUsesBigEndian32BitFields() {
        assertArrayEquals(
                new byte[]{0x00, 0x00, 0x27, 0x10, 0x00, 0x01, 0x27, (byte) 0xD0, 0x00, 0x64},
                CmfProtocolUtils.buildGoalsPayload(10_000, 75_728, 100)
        );
    }

    @Test
    public void goalsPayloadClampsOutOfRangeValuesInsteadOfThrowing() {
        // A calories goal above 0xffff used to throw and abort device initialization
        final byte[] tooBig = CmfProtocolUtils.buildGoalsPayload(
                Integer.MAX_VALUE, Integer.MAX_VALUE, 70_000);
        assertEquals(10, tooBig.length);
        assertEquals((byte) 0xFF, tooBig[8]);
        assertEquals((byte) 0xFF, tooBig[9]);

        final byte[] negative = CmfProtocolUtils.buildGoalsPayload(-1, -1, -1);
        assertArrayEquals(new byte[10], negative);
    }

    @Test
    public void contactsPayloadIs57BytesPerContactAndLimitedTo20() {
        final List<Contact> contacts = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            final int n = i;
            contacts.add(new Contact() {
                @Override public String getContactId() { return String.valueOf(n); }
                @Override public String getName() { return "Name " + n; }
                @Override public String getNumber() { return "+123" + n; }
            });
        }

        final byte[] payload = CmfProtocolUtils.buildContactsPayload(contacts);
        assertEquals(20 * 57, payload.length);
        assertEquals('N', payload[0]);
        assertEquals('+', payload[32]);
    }

    @Test
    public void alarmsPayloadMatchesTheEncodingSentToTheWatch() {
        final List<Alarm> alarms = new ArrayList<>();
        alarms.add(testAlarm(2, true));

        final byte[] payload = CmfProtocolUtils.buildAlarmsPayload(alarms);

        assertEquals(40, payload.length);
        // seconds since midnight, 13:30 = 48600 = 0xBDD8
        assertEquals(0x00, payload[0]);
        assertEquals(0x00, payload[1]);
        assertEquals((byte) 0xBD, payload[2]);
        assertEquals((byte) 0xD8, payload[3]);
        // sequential slot index, not the alarm position
        assertEquals(0x00, payload[4]);
        assertEquals(0x01, payload[5]);
        assertEquals(Alarm.ALARM_MON | Alarm.ALARM_FRI, payload[6]);
        assertEquals((byte) 0xff, payload[7]);
        for (int i = 8; i < 32; i++) {
            assertEquals("reserved byte " + i, 0, payload[i]);
        }
        // 8 label bytes, right aligned at byte 32
        assertEquals(0x00, payload[32]);
        assertEquals('W', payload[33]);
        assertEquals('p', payload[39]);
    }

    @Test
    public void alarmsPayloadSkipsUnusedAlarmsAndCapsAtSlotCount() {
        final List<Alarm> alarms = new ArrayList<>();
        alarms.add(testAlarm(0, false));
        for (int i = 1; i <= 8; i++) {
            alarms.add(testAlarm(i, true));
        }

        final byte[] payload = CmfProtocolUtils.buildAlarmsPayload(alarms);
        assertEquals(CmfProtocolUtils.MAX_ALARMS * CmfProtocolUtils.ALARM_RECORD_SIZE, payload.length);
        assertEquals(0x00, payload[4]);
        assertEquals(0x01, payload[44]);
        assertEquals(0x04, payload[164]);
    }

    @Test
    public void timeGuardAllowsNormalAndRejectsLargeBackwardsJump() {
        assertTrue(CmfProtocolUtils.shouldSendTime(1000, 0));
        assertTrue(CmfProtocolUtils.shouldSendTime(1000, 1050));
        assertFalse(CmfProtocolUtils.shouldSendTime(1000, 2000));
    }

    private static Alarm testAlarm(final int position, final boolean used) {
        return new Alarm() {
            @Override public int getPosition() { return position; }
            @Override public boolean getEnabled() { return true; }
            @Override public boolean getUnused() { return !used; }
            @Override public boolean getSmartWakeup() { return false; }
            @Override public Integer getSmartWakeupInterval() { return null; }
            @Override public boolean getSnooze() { return false; }
            @Override public int getRepetition() { return Alarm.ALARM_MON | Alarm.ALARM_FRI; }
            @Override public boolean isRepetitive() { return true; }
            @Override public boolean getRepetition(int dow) { return false; }
            @Override public int getHour() { return 13; }
            @Override public int getMinute() { return 30; }
            @Override public String getTitle() { return "Wake up"; }
            @Override public String getDescription() { return ""; }
            @Override public int getSoundCode() { return 0; }
            @Override public boolean getBacklight() { return false; }
        };
    }
}
