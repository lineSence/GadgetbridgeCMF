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
package nodomain.freeyourgadget.gadgetbridge.service.devices.cmfwatchpro.recorder;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Everything the microphone PoC needs, without adb: start and stop a recording, run the SCO
 * self test, sweep the capture presets and sample rates when the link is up but silent, pick the
 * duration, the output format and the output folder, and read the report of the last run right on
 * the phone.
 *
 * <p>The UI is built in code on purpose. The screen ships in the debug source set only (see
 * {@code app/src/debug/AndroidManifest.xml}), and building it programmatically keeps it from
 * adding layouts, strings or styles to the shared resources of the app.</p>
 */
public class CmfRecorderActivity extends Activity implements CmfRecorderState.Listener {
    private static final Logger LOG = LoggerFactory.getLogger(CmfRecorderActivity.class);

    /** Action used by the device settings entry, so no explicit class name is needed there. */
    public static final String ACTION_OPEN = "nodomain.freeyourgadget.gadgetbridge.cmf.recorder.OPEN";

    private static final int REQUEST_PERMISSIONS = 4711;
    private static final int REQUEST_FOLDER = 4712;

    private static final long REFRESH_INTERVAL_MILLIS = 500L;
    private static final int MAX_LISTED_FILES = 12;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView statusView;
    private TextView timerView;
    private TextView folderView;
    private TextView reportView;
    private TextView filesView;
    private Button recordButton;
    private CheckBox phoneMicCheckBox;

    /** Set while {@link CmfSourceProbe} is walking the matrix on a background thread. */
    private volatile boolean probeRunning;
    /** Last sweep report, shown instead of the recorder report until the next recording. */
    private volatile String probeReport;

    private final Runnable refreshTicker = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, REFRESH_INTERVAL_MILLIS);
        }
    };

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("CMF диктофон");
        setContentView(buildContentView());
        requestMissingPermissions();
        refresh();
    }

    @Override
    protected void onStart() {
        super.onStart();
        CmfRecorderState.addListener(this);
        handler.post(refreshTicker);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(refreshTicker);
        CmfRecorderState.removeListener(this);
        super.onStop();
    }

    @Override
    public void onRecorderStateChanged() {
        refresh();
    }

    // region UI construction

    private View buildContentView() {
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        final int padding = dp(16);
        root.setPadding(padding, padding, padding, padding);

        statusView = addTextView(root, "", 18f, true);
        timerView = addTextView(root, "00:00", 34f, true);
        addTextView(root, "Запись идёт с микрофона часов через Bluetooth SCO. "
                + "Часы должны быть подключены как гарнитура (профиль звонков), а не только по BLE.",
                12f, false);

        recordButton = addButton(root, "Начать запись", new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                onRecordButtonClicked();
            }
        });

        addButton(root, "Самотест SCO (10 секунд)", new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                if (!ensurePermissions()) {
                    return;
                }
                CmfMicRecorderService.dispatch(CmfRecorderActivity.this,
                        CmfMicRecorderService.ACTION_SELF_TEST, 10, false);
                toast("Самотест запущен, говорите в часы");
            }
        });

        addButton(root, "Контрольный прогон с микрофона телефона (10 с)", new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                if (!ensurePermissions()) {
                    return;
                }
                CmfMicRecorderService.dispatch(CmfRecorderActivity.this,
                        CmfMicRecorderService.ACTION_SELF_TEST, 10, true);
                toast("Контрольный прогон запущен");
            }
        });

        addSectionTitle(root, "Диагностика тишины");
        addTextView(root, "Если канал поднимается, но запись пустая: перебор источников записи "
                        + "и частот (16 кГц mSBC и 8 кГц CVSD) по 2,5 секунды на комбинацию. "
                        + "Говорите в часы всё время скана и не выходите с этого экрана.",
                12f, false);

        addButton(root, "Скан источников (MODE_IN_COMMUNICATION)", new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                startProbe(false);
            }
        });

        addButton(root, "Скан источников (MODE_IN_CALL)", new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                startProbe(true);
            }
        });

        addSectionTitle(root, "Настройки");

        addTextView(root, "Длительность записи", 13f, true);
        final Spinner durationSpinner = addSpinner(root, CmfRecorderPrefs.DURATION_OPTION_LABELS,
                CmfRecorderPrefs.indexOfDuration(CmfRecorderPrefs.getDurationSeconds(this)));
        durationSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(final AdapterView<?> parent, final View view,
                                       final int position, final long id) {
                CmfRecorderPrefs.setDurationSeconds(CmfRecorderActivity.this,
                        CmfRecorderPrefs.DURATION_OPTIONS_SECONDS[position]);
            }

            @Override
            public void onNothingSelected(final AdapterView<?> parent) {
                // keep the stored value
            }
        });

        addTextView(root, "Формат файла", 13f, true);
        final Spinner formatSpinner = addSpinner(root, CmfRecorderPrefs.FORMAT_LABELS,
                CmfRecorderPrefs.indexOfFormat(CmfRecorderPrefs.getFormat(this)));
        formatSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(final AdapterView<?> parent, final View view,
                                       final int position, final long id) {
                CmfRecorderPrefs.setFormat(CmfRecorderActivity.this,
                        CmfRecorderPrefs.FORMAT_OPTIONS[position]);
            }

            @Override
            public void onNothingSelected(final AdapterView<?> parent) {
                // keep the stored value
            }
        });

        phoneMicCheckBox = new CheckBox(this);
        phoneMicCheckBox.setText("Писать с микрофона телефона (без SCO)");
        phoneMicCheckBox.setChecked(CmfRecorderPrefs.isPhoneMic(this));
        phoneMicCheckBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(final CompoundButton buttonView, final boolean isChecked) {
                CmfRecorderPrefs.setPhoneMic(CmfRecorderActivity.this, isChecked);
            }
        });
        root.addView(phoneMicCheckBox);

        addTextView(root, "Папка вывода", 13f, true);
        folderView = addTextView(root, "", 12f, false);

        addButton(root, "Выбрать папку вывода", new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                pickFolder();
            }
        });

        addButton(root, "Сбросить папку (хранить в памяти приложения)", new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                CmfRecorderPrefs.setFolderUri(CmfRecorderActivity.this, null);
                refresh();
            }
        });

        addSectionTitle(root, "Отчёт последнего прогона");
        reportView = addTextView(root, "", 12f, false);
        reportView.setTypeface(Typeface.MONOSPACE);

        addSectionTitle(root, "Записи");
        filesView = addTextView(root, "", 12f, false);
        filesView.setTypeface(Typeface.MONOSPACE);

        final ScrollView scrollView = new ScrollView(this);
        scrollView.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return scrollView;
    }

    private void addSectionTitle(final LinearLayout parent, final String text) {
        final TextView view = addTextView(parent, text, 15f, true);
        final LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) view.getLayoutParams();
        params.topMargin = dp(20);
        view.setLayoutParams(params);
    }

    private TextView addTextView(final LinearLayout parent, final String text,
                                 final float sizeSp, final boolean bold) {
        final TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(6);
        parent.addView(view, params);
        return view;
    }

    private Button addButton(final LinearLayout parent, final String text,
                             final View.OnClickListener listener) {
        final Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(8);
        parent.addView(button, params);
        return button;
    }

    private Spinner addSpinner(final LinearLayout parent, final String[] labels,
                               final int selectedIndex) {
        final Spinner spinner = new Spinner(this);
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, Arrays.asList(labels));
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(selectedIndex, false);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        parent.addView(spinner, params);
        return spinner;
    }

    private int dp(final int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    // endregion

    private void onRecordButtonClicked() {
        if (CmfRecorderState.isBusy() || CmfMicRecorderService.isRecordingSessionActive()) {
            CmfMicRecorderService.dispatch(this, CmfMicRecorderService.ACTION_STOP);
            return;
        }

        if (probeRunning) {
            toast("Дождитесь окончания скана");
            return;
        }

        if (!ensurePermissions()) {
            return;
        }

        probeReport = null;

        CmfMicRecorderService.dispatch(
                this,
                CmfMicRecorderService.ACTION_START,
                CmfRecorderPrefs.getDurationSeconds(this),
                CmfRecorderPrefs.isPhoneMic(this)
        );
    }

    /**
     * Walks the capture preset and sample rate matrix on a background thread. The screen stays in
     * the foreground for the whole run, which is what keeps the microphone accessible without a
     * foreground service.
     */
    private void startProbe(final boolean inCallMode) {
        if (probeRunning) {
            toast("Скан уже идёт");
            return;
        }

        if (CmfRecorderState.isBusy() || CmfMicRecorderService.isRecordingSessionActive()) {
            toast("Сначала остановите запись");
            return;
        }

        if (!ensurePermissions()) {
            return;
        }

        probeRunning = true;
        probeReport = "Скан идёт, говорите в часы…\n";
        refresh();
        toast("Скан запущен, говорите в часы");

        final Context context = getApplicationContext();
        final String folderUri = CmfRecorderPrefs.getFolderUri(this);

        new Thread(new Runnable() {
            @Override
            public void run() {
                String result;
                try {
                    result = CmfSourceProbe.run(
                            context, CmfSourceProbe.DEFAULT_MILLIS_PER_COMBO, inCallMode);
                } catch (final Throwable t) {
                    LOG.warn("Probe crashed", t);
                    result = "probeError=" + t + "\n";
                }

                final File file = CmfSourceProbe.writeReport(context, result);
                if (file != null) {
                    result = result + "reportFile=" + file.getAbsolutePath() + "\n";

                    if (folderUri != null) {
                        final String exported = CmfRecorderExporter.export(
                                context, file, "text/plain", folderUri);
                        if (exported != null) {
                            result = result + "exportedTo=" + exported + "\n";
                        }
                    }
                }

                final String finalResult = result;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        probeRunning = false;
                        probeReport = finalResult;
                        refresh();
                    }
                });
            }
        }, "cmf-source-probe").start();
    }

    private void pickFolder() {
        try {
            final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(intent, REQUEST_FOLDER);
        } catch (final Exception e) {
            LOG.warn("No folder chooser available", e);
            toast("На этом устройстве нет системного выбора папок");
        }
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != REQUEST_FOLDER || resultCode != RESULT_OK || data == null) {
            return;
        }

        final Uri treeUri = data.getData();
        if (treeUri == null) {
            return;
        }

        try {
            getContentResolver().takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            );
        } catch (final Exception e) {
            LOG.warn("Could not persist the folder permission", e);
        }

        CmfRecorderPrefs.setFolderUri(this, treeUri.toString());
        refresh();
    }

    private boolean ensurePermissions() {
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
            return true;
        }

        requestMissingPermissions();
        toast("Нужно разрешение на запись звука");
        return false;
    }

    private void requestMissingPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }

        final List<String> missing = new ArrayList<>();
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && !hasPermission("android.permission.BLUETOOTH_CONNECT")) {
            missing.add("android.permission.BLUETOOTH_CONNECT");
        }
        if (Build.VERSION.SDK_INT >= 33
                && !hasPermission("android.permission.POST_NOTIFICATIONS")) {
            missing.add("android.permission.POST_NOTIFICATIONS");
        }

        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
        }
    }

    private boolean hasPermission(final String permission) {
        return checkPermission(permission, android.os.Process.myPid(), android.os.Process.myUid())
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(final int requestCode, final String[] permissions,
                                           final int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refresh();
    }

    private void refresh() {
        final boolean busy = CmfRecorderState.isBusy()
                || CmfMicRecorderService.isRecordingSessionActive();

        statusView.setText(probeRunning ? "Идёт скан источников…" : describeState());
        statusView.setTextColor(busy || probeRunning ? Color.RED : Color.GRAY);
        timerView.setText(CmfRecorderStatus.formatElapsed(CmfRecorderState.getElapsedMillis()));
        recordButton.setText(busy ? "Остановить запись" : "Начать запись");

        final String folderUri = CmfRecorderPrefs.getFolderUri(this);
        folderView.setText(folderUri != null
                ? CmfRecorderExporter.describe(folderUri)
                : "память приложения: " + CmfMicRecorder.defaultOutputDir(this).getAbsolutePath());

        final String probe = probeReport;
        reportView.setText(probe != null ? probe : lastReport());
        filesView.setText(listRecordings());
    }

    private String describeState() {
        switch (CmfRecorderState.getState()) {
            case CONNECTING:
                return "Поднимаем SCO-канал до часов…";
            case RECORDING:
                return "Идёт запись";
            case STOPPED:
                final String verdict = CmfRecorderState.getLastVerdict();
                return verdict.isEmpty() ? "Запись завершена" : verdict;
            case IDLE:
            default:
                return "Готов к записи";
        }
    }

    private String lastReport() {
        final String inMemory = CmfRecorderState.getLastReport();
        if (!inMemory.isEmpty()) {
            final String exported = CmfRecorderState.getLastExportTarget();
            return exported.isEmpty()
                    ? inMemory
                    : inMemory + "exportedTo=" + exported + "\n";
        }

        final File newest = newestReportFile();
        if (newest == null) {
            return "Отчётов пока нет. Запустите самотест.";
        }
        return readFile(newest);
    }

    private File newestReportFile() {
        final File[] files = CmfMicRecorder.defaultOutputDir(this).listFiles();
        if (files == null) {
            return null;
        }

        File newest = null;
        for (final File file : files) {
            if (!file.getName().endsWith(".txt")) {
                continue;
            }
            if (newest == null || file.lastModified() > newest.lastModified()) {
                newest = file;
            }
        }
        return newest;
    }

    private String readFile(final File file) {
        final StringBuilder sb = new StringBuilder();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(file));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (final IOException e) {
            return "Не удалось прочитать отчёт: " + e.getMessage();
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (final IOException ignored) {
                    // nothing we can do
                }
            }
        }
        return sb.toString();
    }

    private String listRecordings() {
        final File dir = CmfMicRecorder.defaultOutputDir(this);
        final File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return "Пусто. Файлы будут здесь: " + dir.getAbsolutePath();
        }

        final List<File> audio = new ArrayList<>();
        for (final File file : files) {
            final String name = file.getName();
            if (name.endsWith(".wav") || name.endsWith(".m4a")) {
                audio.add(file);
            }
        }

        if (audio.isEmpty()) {
            return "Записей пока нет.";
        }

        Collections.sort(audio, new Comparator<File>() {
            @Override
            public int compare(final File left, final File right) {
                return Long.compare(right.lastModified(), left.lastModified());
            }
        });

        final StringBuilder sb = new StringBuilder();
        final int count = Math.min(audio.size(), MAX_LISTED_FILES);
        for (int i = 0; i < count; i++) {
            final File file = audio.get(i);
            sb.append(String.format(Locale.ROOT, "%s  %.1f КБ%n",
                    file.getName(), file.length() / 1024f));
        }
        if (audio.size() > count) {
            sb.append("… и ещё ").append(audio.size() - count).append('\n');
        }
        sb.append('\n').append(dir.getAbsolutePath());
        return sb.toString();
    }

    private void toast(final String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    /** Intent that opens this screen, used by the notification and the device settings entry. */
    public static Intent openIntent(final Context context) {
        final Intent intent = new Intent(context, CmfRecorderActivity.class);
        intent.setAction(ACTION_OPEN);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return intent;
    }
}
