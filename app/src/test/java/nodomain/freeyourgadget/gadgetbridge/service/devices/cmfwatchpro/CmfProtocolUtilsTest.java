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
    public void alarmsPayloadUsesProtocolFieldOrderAndPutsLabelAtByteEight() {
        final List<Alarm> alarms = List.of(new Alarm() {
            @Override public int getPosition() { return 2; }
            @Override public boolean getEnabled() { return true; }
            @Override public boolean getUnused() { return false; }
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
        });

        final byte[] payload = CmfProtocolUtils.buildAlarmsPayload(alarms);
        assertEquals(40, payload.length);
        assertEquals(0x00, payload[0]);
        assertEquals(0x00, payload[1]);
        assertEquals((byte) 0xBD, payload[2]);
        assertEquals((byte) 0xD8, payload[3]);
        assertEquals(0x02, payload[4]);
        assertEquals(0x01, payload[5]);
        assertEquals(Alarm.ALARM_MON | Alarm.ALARM_FRI, payload[6]);
        assertEquals(0x00, payload[7]);
        assertEquals('W', payload[8]);
        assertEquals('a', payload[9]);
        assertEquals('p', payload[14]);
        assertEquals(0, payload[15]);
        assertEquals(0, payload[39]);
    }

    @Test
    public void timeGuardAllowsNormalAndRejectsLargeBackwardsJump() {
        assertTrue(CmfProtocolUtils.shouldSendTime(1000, 0));
        assertTrue(CmfProtocolUtils.shouldSendTime(1000, 1050));
        assertFalse(CmfProtocolUtils.shouldSendTime(1000, 2000));
    }
}