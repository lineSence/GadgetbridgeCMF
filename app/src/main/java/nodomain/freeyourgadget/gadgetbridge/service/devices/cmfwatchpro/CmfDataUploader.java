/*  Copyright (C) 2024 José Rebelo

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
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.cmfwatchpro;

import android.content.Context;
import android.net.Uri;
import android.widget.Toast;

import org.apache.commons.lang3.ArrayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Locale;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cmfwatchpro.watchface.CmfDialList;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cmfwatchpro.watchface.CmfPhotoWatchface;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cmfwatchpro.watchface.CmfWatchfacePrefs;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cmfwatchpro.watchface.CmfWatchfaceSlots;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

/**
 * Sends watchfaces, firmware and AGPS data to the watch.
 *
 * <h2>Why a watchface must replace another one</h2>
 *
 * <p>The watch keeps a fixed number of dial slots and reports that limit in the dial list. A photo
 * watchface is appended to that list, so a full watch simply stores the file and never shows it.
 * The official app solves this by asking which watchface to replace, and this class does the same:
 * the upload refuses to start until a target is chosen on the watchface slots screen, and the slot
 * is freed before the transfer begins.</p>
 */
public class CmfDataUploader implements CmfCharacteristic.Handler {
    private static final Logger LOG = LoggerFactory.getLogger(CmfDataUploader.class);

    /** The watch accepted the file and switched to it. */
    private static final int FINISH_ACTIVATED = 0x01;

    /** The watch stored the file but refused to show it. */
    private static final int FINISH_STORED_ONLY = 0x0a;

    private final CmfWatchProSupport mSupport;

    private CmfFwHelper fwHelper;

    /** Watchface the current upload replaces. Only valid while {@link #fwHelper} is set. */
    private int replacedDialId;

    public CmfDataUploader(final CmfWatchProSupport support) {
        this.mSupport = support;
    }

    @Override
    public void onCommand(final CmfCommand cmd, final byte[] payload) {
        switch (cmd) {
            case DATA_TRANSFER_WATCHFACE_INIT_1_REPLY: {
                if (payload[0] != 0x01) {
                    abort("Часы отказались начать передачу, код " + hex(payload[0]));
                    return;
                }

                if (fwHelper == null) {
                    LOG.warn("Got a transfer init 1 reply without a file");
                    return;
                }

                if (fwHelper.isPhotoWatchface()) {
                    mSupport.sendData(
                            "transfer watchface init 2 request",
                            CmfCommand.DATA_TRANSFER_WATCHFACE_INIT_2_REQUEST,
                            buildPhotoInit2Payload()
                    );
                } else {
                    mSupport.sendData(
                            "transfer watchface replace init 2 request",
                            CmfCommand.DATA_TRANSFER_WATCHFACE_REPLACE_INIT_2_REQUEST,
                            buildReplaceInit2Payload()
                    );
                }

                return;
            }
            case DATA_TRANSFER_FIRMWARE_INIT_1_REPLY: {
                if (payload[0] != 0x01) {
                    LOG.warn("Got unexpected firmware init 2 reply {}", payload[0]);
                    fwHelper = null;
                    return;
                }

                final ByteBuffer buf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
                // FIXME version a.b.c.d... how to know? this was from 11.0.0.57
                buf.put((byte) (0x0b));
                buf.put((byte) (0x00));
                buf.put((byte) (0x00));
                buf.put((byte) (0x39));

                mSupport.sendFirmware(
                        "transfer firmware init request",
                        CmfCommand.DATA_TRANSFER_FIRMWARE_INIT_2_REQUEST,
                        buf.array()
                );
                return;
            }
            case DATA_TRANSFER_AGPS_INIT_REPLY:
            case DATA_TRANSFER_FIRMWARE_INIT_2_REPLY:
            case DATA_TRANSFER_WATCHFACE_INIT_2_REPLY:
            case DATA_TRANSFER_WATCHFACE_REPLACE_INIT_2_REPLY:
                if (payload[0] != 0x01) {
                    abort("Часы отклонили описание файла, код " + hex(payload[0]));
                    return;
                }

                setDeviceBusy();
                updateProgress(0, true);

                return;
            case DATA_TRANSFER_WATCHFACE_FINISH_ACK_1:
                handleAck1(CmfCommand.DATA_TRANSFER_WATCHFACE_FINISH_ACK_2, payload);
                return;
            case DATA_TRANSFER_FIRMWARE_FINISH_ACK_1:
                // TODO: Confirm if this is being sent in the right characteristic, although it looks
                //  like it does not matter, since it restarts right away
                handleAck1(CmfCommand.DATA_TRANSFER_FIRMWARE_FINISH_ACK_2, payload);
                return;
            case DATA_TRANSFER_AGPS_FINISH_ACK_1:
                handleAck1(CmfCommand.DATA_TRANSFER_AGPS_FINISH_ACK_2, payload);
                return;
            case DATA_CHUNK_REQUEST_AGPS:
                if (fwHelper == null || !fwHelper.isAgps()) {
                    LOG.warn("We are not sending AGPS - refusing request");
                    return;
                }
                handleChunkRequest(CmfCommand.DATA_CHUNK_REQUEST_AGPS, payload);
                return;
            case DATA_CHUNK_REQUEST_WATCHFACE:
                if (fwHelper == null || !fwHelper.isWatchface()) {
                    LOG.warn("We are not sending a watchface - refusing request");
                    return;
                }
                handleChunkRequest(CmfCommand.DATA_CHUNK_WRITE_WATCHFACE, payload);
                return;
            case DATA_CHUNK_REQUEST_FIRMWARE:
                if (fwHelper == null || !fwHelper.isFirmware()) {
                    LOG.warn("We are not sending firmware - refusing request");
                    return;
                }
                handleChunkRequest(CmfCommand.DATA_CHUNK_WRITE_FIRMWARE, payload);
                return;
        }

        LOG.warn("Got unknown data command {}", cmd);
    }

    public void onInstallApp(final Uri uri) {
        if (fwHelper != null) {
            LOG.warn("Already installing {}", fwHelper.getUri());
            toast("Предыдущая передача ещё не закончена", GB.WARN);
            return;
        }

        fwHelper = new CmfFwHelper(uri, mSupport.getContext());
        if (!fwHelper.isValid()) {
            LOG.warn("Uri {} is not valid", uri);
            fwHelper = null;
            toast("Файл не подходит для этих часов", GB.ERROR);
            return;
        }

        if (fwHelper.isWatchface()) {
            startWatchfaceTransfer();
            return;
        }

        /* FIXME: This is disabled until we figure out how to send the firmware version
        if (fwHelper.isFirmware()) {
            mSupport.sendCommand(
                    "transfer firmware init request",
                    CmfCommand.DATA_TRANSFER_FIRMWARE_INIT_1_REQUEST,
                    (byte) 0xa5
            );

            return;
        }
        */

        LOG.warn("Unsupported fwHelper for {}", fwHelper.getUri());
        fwHelper = null;
    }

    /**
     * Checks the replacement target and frees its slot before the transfer starts.
     *
     * <p>A photo watchface is appended by the watch, so the old watchface has to be deleted first,
     * otherwise a full watch keeps the file without ever listing it. A structured watchface is
     * replaced in place instead, and the target id travels in the second init request.</p>
     */
    private void startWatchfaceTransfer() {
        final CmfWatchfaceSlots slots = new CmfWatchfaceSlots(mSupport.getContext());

        if (!slots.hasTarget()) {
            LOG.warn("No replacement target selected");
            fwHelper = null;
            toast("Сначала выберите заменяемый циферблат на экране «Циферблаты часов»",
                    GB.WARN);
            return;
        }

        replacedDialId = slots.getTargetId();
        final List<Integer> ids = slots.getIds();

        if (!ids.isEmpty() && !ids.contains(replacedDialId)) {
            LOG.warn("Selected dial {} is not on the watch any more", replacedDialId);
            fwHelper = null;
            toast("Выбранного циферблата больше нет на часах. Обновите список.", GB.WARN);
            return;
        }

        if (fwHelper.isPhotoWatchface() && ids.contains(replacedDialId)) {
            final List<Integer> remaining = CmfDialList.withoutId(ids, replacedDialId);
            if (remaining.isEmpty()) {
                LOG.warn("Refusing to delete the last dial on the watch");
                fwHelper = null;
                toast("Нельзя удалить единственный циферблат часов", GB.WARN);
                return;
            }

            LOG.info("Freeing dial slot {}, {} dials left", replacedDialId, remaining.size());
            mSupport.sendCommand(
                    "free the dial slot",
                    CmfCommand.DIAL_LIST_SET,
                    CmfDialList.buildOrder(remaining)
            );
        }

        LOG.info("Sending {} bytes, replacing dial {}", fwHelper.getBytes().length, replacedDialId);

        mSupport.sendData(
                "transfer watchface init request",
                CmfCommand.DATA_TRANSFER_WATCHFACE_INIT_1_REQUEST,
                (byte) 0xa5
        );
    }

    /**
     * Second init request for a photo watchface. The descriptor tells the firmware where to draw
     * the clock on top of the photo, and a descriptor of the wrong length makes the watch store
     * the file without ever showing it.
     */
    private byte[] buildPhotoInit2Payload() {
        final int fileSize = fwHelper.getBytes().length;
        final CmfWatchfacePrefs prefs = new CmfWatchfacePrefs(mSupport.getContext());
        final CmfPhotoWatchface.Params params = prefs.getParams();

        LOG.info("Sending photo watchface: size={}, style={}, position={},{}",
                fileSize, params.styleId, params.positionX, params.positionY);

        return CmfPhotoWatchface.buildDescriptor(params, fileSize);
    }

    /**
     * Second init request for a watchface exported by the official app. Unlike everything else in
     * the command protocol, this payload is little endian:
     *
     * <pre>
     * kind u8, old id u32 LE, new id u32 LE, file size u32 LE
     * </pre>
     *
     * <p>The old id must already be installed on the watch. The new id is sent as the same value,
     * so the watchface takes over the slot it replaces. This path is not confirmed on a watch yet.</p>
     */
    private byte[] buildReplaceInit2Payload() {
        final int fileSize = fwHelper.getBytes().length;

        LOG.info("Sending structured watchface: size={}, replacing dial {}", fileSize, replacedDialId);

        final ByteBuffer buf = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 0x02);
        buf.putInt(replacedDialId);
        buf.putInt(replacedDialId);
        buf.putInt(fileSize);
        return buf.array();
    }

    private void handleChunkRequest(final CmfCommand commandReply, final byte[] payload) {
        final ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        final int offset = buf.getInt();
        final int length = buf.getInt();
        final int progress = buf.get();

        LOG.debug("Got chunk request: offset={}, length={}, progress={}", offset, length, progress);

        final TransactionBuilder builder = mSupport.createTransactionBuilder("send chunk offset " + offset);
        updateProgress(builder, progress, true);
        if (commandReply == CmfCommand.DATA_CHUNK_WRITE_FIRMWARE) {
            mSupport.sendFirmware(
                    "send firmware chunk",
                    commandReply,
                    ArrayUtils.subarray(fwHelper.getBytes(), offset, offset + length)
            );
        } else {
            mSupport.sendData(
                    "send data chunk",
                    commandReply,
                    ArrayUtils.subarray(fwHelper.getBytes(), offset, offset + length)
            );
        }
    }

    private void handleAck1(final CmfCommand commandReply, final byte[] payload) {
        final int code = payload.length > 0 ? payload[0] & 0xff : -1;
        final boolean watchface = fwHelper != null && fwHelper.isWatchface();

        LOG.debug("Got transfer finish ack 1, code={}", code);

        unsetDeviceBusy();
        updateProgress(100, false);
        mSupport.sendData("transfer finish", commandReply, (byte) 0xa5);

        if (watchface) {
            reportWatchfaceResult(code);

            // The list on the phone is now out of date in any case, so read it back.
            mSupport.sendCommand(
                    "refresh dial list",
                    CmfCommand.DIAL_LIST_SET,
                    CmfDialList.buildQuery()
            );
        }

        // Released here as well as on failure, otherwise a second upload in the same session is
        // refused with "already installing".
        fwHelper = null;
    }

    /** Turns the finish code into something the user can read without a computer attached. */
    private void reportWatchfaceResult(final int code) {
        final String text;
        final int severity;

        if (code == FINISH_ACTIVATED) {
            text = "Часы приняли циферблат и включили его";
            severity = GB.INFO;
        } else if (code == FINISH_STORED_ONLY) {
            text = "Часы сохранили файл, но не включили его (код 0a). Обычно это неверный формат файла или нет свободного места.";
            severity = GB.WARN;
        } else {
            text = "Часы ответили на передачу кодом " + hex((byte) code);
            severity = GB.WARN;
        }

        new CmfWatchfaceSlots(mSupport.getContext()).setLastResult(text);
        toast(text, severity);
    }

    /** Stops the current transfer and tells the user why. */
    private void abort(final String text) {
        LOG.warn("Aborting transfer: {}", text);

        final Context context = mSupport.getContext();
        if (context != null) {
            new CmfWatchfaceSlots(context).setLastResult(text);
        }

        unsetDeviceBusy();
        toast(text, GB.ERROR);
        fwHelper = null;
    }

    private void toast(final String text, final int severity) {
        final Context context = mSupport.getContext();
        if (context == null) {
            return;
        }
        GB.toast(context, text, Toast.LENGTH_LONG, severity);
    }

    private static String hex(final byte value) {
        return String.format(Locale.ROOT, "0x%02x", value & 0xff);
    }

    private void updateProgress(final int progressPercent, boolean ongoing) {
        final TransactionBuilder builder = mSupport.createTransactionBuilder("update data upload progress to " + progressPercent);
        updateProgress(builder, progressPercent, ongoing);
        builder.queue();
    }

    private void updateProgress(final TransactionBuilder builder, final int progressPercent, boolean ongoing) {
        final int uploadMessage;
        if (fwHelper != null && fwHelper.isWatchface()) {
            uploadMessage = R.string.uploading_watchface;
        } else {
            uploadMessage = R.string.updating_firmware;
        }

        builder.setProgress(
                uploadMessage,
                ongoing,
                progressPercent
        );
    }

    private void setDeviceBusy() {
        final GBDevice device = mSupport.getDevice();
        device.setBusyTask(R.string.updating_firmware, mSupport.getContext());
        device.sendDeviceUpdateIntent(mSupport.getContext());
    }

    private void unsetDeviceBusy() {
        final GBDevice device = mSupport.getDevice();
        if (device != null && device.isConnected()) {
            if (device.isBusy()) {
                device.unsetBusyTask();
                device.sendDeviceUpdateIntent(mSupport.getContext());
            }
            device.sendDeviceUpdateIntent(mSupport.getContext());
        }
    }
}
