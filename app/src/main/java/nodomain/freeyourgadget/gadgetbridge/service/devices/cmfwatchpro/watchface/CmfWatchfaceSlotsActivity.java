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

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;

/**
 * Shows the watchfaces installed on the watch and lets the user pick the one the next upload
 * replaces.
 *
 * <p>The watch has a fixed number of dial slots and reports that limit itself. The official app
 * asks which watchface to replace for the same reason, and without a chosen target the upload here
 * refuses to start, because an appended watchface on a full watch is stored and never shown.</p>
 *
 * <p>The screen never talks to the watch directly. It writes a request key and lets the device
 * service send the command, then redraws when the answer lands in the shared preferences. That is
 * the same route the rest of the device settings use.</p>
 */
public class CmfWatchfaceSlotsActivity extends Activity
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    public static final String ACTION_OPEN =
            "nodomain.freeyourgadget.gadgetbridge.cmf.watchface.SLOTS";

    private CmfWatchfaceSlots slots;

    private TextView deviceView;
    private TextView statusView;
    private RadioGroup listGroup;
    private TextView emptyView;
    private TextView resultView;
    private TextView rawView;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Циферблаты часов");

        slots = new CmfWatchfaceSlots(this);

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        final int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        deviceView = addText(root, "", 14, dp(4));
        addHeader(root, "Установленные циферблаты");
        statusView = addText(root, "", 14, dp(8));

        listGroup = new RadioGroup(this);
        listGroup.setOrientation(RadioGroup.VERTICAL);
        root.addView(listGroup, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        emptyView = addText(root,
                "Список ещё не прочитан. Нажмите «Запросить список у часов».", 14, dp(8));

        addButton(root, "Запросить список у часов", v -> requestList());
        addButton(root, "Сделать выбранный активным", v -> activateSelected());
        addButton(root, "Удалить выбранный с часов", v -> confirmDelete());
        addButton(root, "Сбросить выбор", v -> clearTarget());

        addHeader(root, "Ответ часов на последнюю загрузку");
        resultView = addText(root, "", 13, dp(8));

        addHeader(root, "Сырые байты ответа");
        rawView = addText(root, "", 12, dp(8));
        rawView.setTypeface(android.graphics.Typeface.MONOSPACE);

        addText(root,
                "Порядок работы: запросите список, выберите циферблат для замены, "
                + "затем откройте редактор и отправьте новый циферблат. Выбор сохраняется.",
                12, dp(16));

        final ScrollView scroll = new ScrollView(this);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        slots.getPreferences().registerOnSharedPreferenceChangeListener(this);
        redraw();
    }

    @Override
    protected void onPause() {
        slots.getPreferences().unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }

    @Override
    public void onSharedPreferenceChanged(final SharedPreferences preferences, final String key) {
        // The device service writes from a Bluetooth thread, so the redraw has to be handed over.
        runOnUiThread(this::redraw);
    }

    private void redraw() {
        final GBDevice device = findDevice();
        if (device == null) {
            deviceView.setText("Часы не подключены. Подключите их в Главном окне приложения.");
        } else {
            deviceView.setText("Устройство: " + device.getName());
        }

        final List<Integer> ids = slots.getIds();
        final int max = slots.getMax();
        final int activeIndex = slots.getActiveIndex();
        final long updatedAt = slots.getUpdatedAt();

        final StringBuilder status = new StringBuilder();
        if (updatedAt == 0L) {
            status.append("Список ни разу не читался.");
        } else {
            status.append("Циферблатов: ").append(ids.size());
            if (max > 0) {
                status.append(" из ").append(max);
            }
            status.append(". Обновлено в ")
                    .append(new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date(updatedAt)))
                    .append(".");
            if (slots.isFull()) {
                status.append("\nСвободных слотов нет. Новый циферблат заменит выбранный.");
            }
        }
        statusView.setText(status.toString());

        listGroup.setOnCheckedChangeListener(null);
        listGroup.removeAllViews();

        for (int i = 0; i < ids.size(); i++) {
            final int id = ids.get(i);

            final StringBuilder label = new StringBuilder(CmfDialList.label(id));
            if (i == activeIndex) {
                label.append("  • активный");
            }
            if (slots.hasTarget() && slots.getTargetId() == id) {
                label.append("  • выбран для замены");
            }

            final RadioButton button = new RadioButton(this);
            button.setId(i + 1);
            button.setText(label.toString());
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            button.setChecked(slots.hasTarget() && slots.getTargetId() == id);
            listGroup.addView(button);
        }

        emptyView.setVisibility(ids.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);

        listGroup.setOnCheckedChangeListener((group, checkedId) -> {
            final int index = checkedId - 1;
            final List<Integer> current = slots.getIds();
            if (index < 0 || index >= current.size()) {
                return;
            }
            slots.setTargetId(current.get(index));
            redraw();
        });

        final String result = slots.getLastResult();
        resultView.setText(result.isEmpty() ? "Пока нет данных." : result);

        final String raw = slots.getRaw();
        rawView.setText(raw.isEmpty() ? "Пока нет данных." : raw);
    }

    private void requestList() {
        sendConfig(CmfWatchfaceSlots.CONFIG_DIAL_LIST_REFRESH, "Запрос отправлен");
    }

    private void activateSelected() {
        if (!requireTarget()) {
            return;
        }
        sendConfig(CmfWatchfaceSlots.CONFIG_DIAL_ACTIVATE, "Команда отправлена");
    }

    private void confirmDelete() {
        if (!requireTarget()) {
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Удалить циферблат")
                .setMessage("Удалить с часов: " + CmfDialList.label(slots.getTargetId())
                        + "?\nВернуть штатный циферблат можно только через Nothing X.")
                .setPositiveButton("Удалить",
                        (dialog, which) -> sendConfig(CmfWatchfaceSlots.CONFIG_DIAL_DELETE,
                                "Команда отправлена"))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void clearTarget() {
        slots.clearTarget();
        toast("Выбор сброшен");
        redraw();
    }

    private boolean requireTarget() {
        if (slots.hasTarget()) {
            return true;
        }
        toast("Сначала выберите циферблат в списке");
        return false;
    }

    /** Hands a request to the device service, which owns the Bluetooth connection. */
    private void sendConfig(final String key, final String okText) {
        final GBDevice device = findDevice();
        if (device == null) {
            toast("Часы не подключены");
            return;
        }

        GBApplication.deviceService(device).onSendConfiguration(key);
        toast(okText);
    }

    /**
     * Finds the connected CMF watch. The name is matched instead of the device type, because the
     * type enum differs between the Watch Pro models and the phone only ever has one of them
     * connected at a time.
     */
    private GBDevice findDevice() {
        final List<GBDevice> devices = GBApplication.app().getDeviceManager().getDevices();

        GBDevice fallback = null;
        for (final GBDevice device : devices) {
            if (!device.isConnected()) {
                continue;
            }
            final String name = device.getName();
            if (name != null && name.toUpperCase(Locale.ROOT).contains("CMF")) {
                return device;
            }
            if (fallback == null) {
                fallback = device;
            }
        }
        return fallback;
    }

    private void toast(final String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private TextView addText(final LinearLayout parent, final String text, final int sizeSp,
                             final int marginBottom) {
        final TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);

        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = marginBottom;
        parent.addView(view, params);
        return view;
    }

    private void addHeader(final LinearLayout parent, final String text) {
        final TextView view = addText(parent, text, 16, dp(4));
        view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        final LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) view.getLayoutParams();
        params.topMargin = dp(12);
        view.setLayoutParams(params);
    }

    private void addButton(final LinearLayout parent, final String text,
                           final android.view.View.OnClickListener listener) {
        final Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(listener);

        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(4);
        parent.addView(button, params);
    }

    private int dp(final int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
