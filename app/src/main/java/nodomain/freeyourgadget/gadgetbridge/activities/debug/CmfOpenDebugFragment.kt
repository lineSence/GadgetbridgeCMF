package nodomain.freeyourgadget.gadgetbridge.activities.debug

import android.os.Bundle
import android.widget.Toast
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.devices.cmfwatchpro.CmfWatchProCoordinator
import nodomain.freeyourgadget.gadgetbridge.model.Alarm
import nodomain.freeyourgadget.gadgetbridge.model.Contact
import nodomain.freeyourgadget.gadgetbridge.model.RecordedDataTypes
import nodomain.freeyourgadget.gadgetbridge.util.GB

/**
 * CMFOPEN protocol smoke tests integrated into Gadgetbridge's existing Debug Activity.
 * These actions use the normal DeviceService/EventHandler path used by production code.
 */
class CmfOpenDebugFragment : AbstractDebugFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.debug_preferences_cmfopen, rootKey)

        onClick(PREF_TIME) {
            runOnCmfDevice { device ->
                GBApplication.deviceService(device).onSetTime()
            }
        }

        onClick(PREF_CONTACTS) {
            runOnCmfDevice { device ->
                val contacts = arrayListOf<Contact>(
                    object : Contact {
                        override fun getContactId() = "cmfopen-test"
                        override fun getName() = "CMFOPEN TEST"
                        override fun getNumber() = "+10000000000"
                    }
                )
                GBApplication.deviceService(device).onSetContacts(contacts)
            }
        }

        onClick(PREF_ALARMS) {
            runOnCmfDevice { device ->
                val alarms = arrayListOf<Alarm>(
                    object : Alarm {
                        override fun getPosition() = 0
                        override fun getEnabled() = true
                        override fun getUnused() = false
                        override fun getSmartWakeup() = false
                        override fun getSmartWakeupInterval(): Int? = null
                        override fun getSnooze() = false
                        override fun getRepetition() = Alarm.ALARM_ONCE.toInt()
                        override fun isRepetitive() = false
                        override fun getRepetition(dow: Int) = false
                        override fun getHour() = 12
                        override fun getMinute() = 34
                        override fun getTitle() = "CMFOPEN TEST ALARM"
                        override fun getDescription() = "CMFOPEN"
                        override fun getSoundCode() = 0
                        override fun getBacklight() = false
                    }
                )
                GBApplication.deviceService(device).onSetAlarms(alarms)
            }
        }

        onClick(PREF_ACTIVITY) {
            runOnCmfDevice { device ->
                GBApplication.deviceService(device).onFetchRecordedData(RecordedDataTypes.TYPE_ACTIVITY)
            }
        }
    }

    private fun runOnCmfDevice(function: (nodomain.freeyourgadget.gadgetbridge.impl.GBDevice) -> Unit) {
        runOnDebugDevices(title = getString(R.string.choose_device)) { device ->
            if (device.deviceCoordinator !is CmfWatchProCoordinator) {
                GB.toast(
                    requireContext(),
                    "CMFOPEN tests are only available for CMF Watch Pro 2",
                    Toast.LENGTH_LONG,
                    GB.WARN
                )
                return@runOnDebugDevices
            }
            function(device)
        }
    }

    companion object {
        private const val PREF_TIME = "cmfopen_test_time"
        private const val PREF_CONTACTS = "cmfopen_test_contacts"
        private const val PREF_ALARMS = "cmfopen_test_alarms"
        private const val PREF_ACTIVITY = "cmfopen_test_activity"
    }
}
