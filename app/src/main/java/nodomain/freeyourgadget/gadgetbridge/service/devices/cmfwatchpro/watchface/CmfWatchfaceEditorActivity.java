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
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.activities.install.FwAppInstallerActivity;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceManager;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;

/**
 * Watchface editor that runs entirely on the phone.
 *
 * <p>The screen does four things:</p>
 *
 * <ol>
 *     <li>pick a photo through the system picker, so no storage permission is needed;</li>
 *     <li>preview it at the real screen size with the clock overlay and the hidden bottom area;</li>
 *     <li>build the watchface file, including an LZ4 round trip self check;</li>
 *     <li>hand the file to the normal Gadgetbridge install flow, which runs the BLE transfer.</li>
 * </ol>
 *
 * <p>The install screen does not search for a target by itself. It reads the device from the
 * intent extra {@link GBDevice#EXTRA_DEVICE} and closes immediately when that extra is missing.
 * The editor therefore resolves the target here, see {@link #sendToWatch()}.</p>
 *
 * <p>The inspector button is a development aid: it dumps the structure of any watchface file, so a
 * watchface exported by Nothing X can be compared byte by byte against what this editor produces.
 * That is the fastest way to correct the container layout if the watch rejects a build.</p>
 *
 * <p>The activity is declared in the debug manifest only, like the rest of the CMF tooling.</p>
 */
public class CmfWatchfaceEditorActivity extends Activity {
    private static final Logger LOG = LoggerFactory.getLogger(CmfWatchfaceEditorActivity.class);

    public static final String ACTION_OPEN =
            "nodomain.freeyourgadget.gadgetbridge.cmf.watchface.OPEN";

    private static final String FILE_PROVIDER_SUFFIX = ".cmf.watchface.files";
    private static final String OUTPUT_DIR_NAME = "Watchfaces";
    private static final String FILE_PREFIX = "cmf-wf-";
    private static final String FILE_SUFFIX = ".bin";

    private static final int REQUEST_PICK_IMAGE = 5711;
    private static final int REQUEST_PICK_WATCHFACE = 5712;

    /** Longest edge kept when decoding the source photo, enough for a 466 pixel screen. */
    private static final int MAX_DECODE_SIZE = 2048;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final CmfPhotoWatchface.Params params = new CmfPhotoWatchface.Params();

    private CmfWatchfacePrefs prefs;

    private Bitmap sourceBitmap;
    private File builtFile;
    private boolean busy;

    private ImageView previewView;
    private TextView statusView;
    private TextView reportView;
    private Button buildButton;
    private Button sendButton;

    public static Intent openIntent(final Context context) {
        final Intent intent = new Intent(context, CmfWatchfaceEditorActivity.class);
        intent.setAction(ACTION_OPEN);
        return intent;
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = new CmfWatchfacePrefs(this);
        final CmfPhotoWatchface.Params stored = prefs.getParams();
        params.styleId = stored.styleId;
        params.positionX = stored.positionX;
        params.positionY = stored.positionY;
        params.colorArgb = stored.colorArgb;

        setContentView(buildLayout());
        restoreLastFile();
        updatePreview();
    }

    private View buildLayout() {
        final int pad = dp(16);

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        root.addView(header("Редактор циферблата"));
        root.addView(body("Фото обрезается до квадрата и масштабируется до 466×466. "
                + "Нижнюю часть кадра часы не показывают. Граница видимой зоны отмечена линией."));

        previewView = new ImageView(this);
        previewView.setAdjustViewBounds(true);
        previewView.setBackgroundColor(Color.DKGRAY);
        final LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                dp(240), dp(240));
        previewParams.topMargin = dp(8);
        previewParams.bottomMargin = dp(8);
        previewView.setLayoutParams(previewParams);
        root.addView(previewView);

        root.addView(button("Выбрать фото", new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                pickImage();
            }
        }));

        root.addView(header("Часы на циферблате"));

        root.addView(body("Стиль"));
        root.addView(styleSpinner());

        root.addView(body("Цвет"));
        root.addView(colorSpinner());

        root.addView(body("Положение по горизонтали"));
        root.addView(slider(CmfPhotoWatchface.FULL_WIDTH, params.positionX,
                new SliderListener() {
                    @Override
                    public void onValue(final int value) {
                        params.positionX = value;
                        onParamsChanged();
                    }
                }));

        root.addView(body("Положение по вертикали"));
        root.addView(slider(CmfPhotoWatchface.VISIBLE_HEIGHT, params.positionY,
                new SliderListener() {
                    @Override
                    public void onValue(final int value) {
                        params.positionY = value;
                        onParamsChanged();
                    }
                }));

        root.addView(header("Сборка и загрузка"));

        buildButton = button("Собрать файл циферблата", new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                buildWatchface();
            }
        });
        root.addView(buildButton);

        sendButton = button("Отправить на часы", new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                sendToWatch();
            }
        });
        sendButton.setEnabled(false);
        root.addView(sendButton);

        root.addView(button("Разобрать файл циферблата", new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                pickWatchfaceToInspect();
            }
        }));

        statusView = body("Фото не выбрано");
        root.addView(statusView);

        reportView = new TextView(this);
        reportView.setTypeface(Typeface.MONOSPACE);
        reportView.setTextSize(12);
        reportView.setPadding(0, dp(8), 0, 0);
        reportView.setTextIsSelectable(true);
        root.addView(reportView);

        final ScrollView scroll = new ScrollView(this);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private Spinner styleSpinner() {
        final Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                CmfPhotoWatchface.STYLE_LABELS));
        spinner.setSelection(indexOf(CmfPhotoWatchface.STYLE_IDS, params.styleId));
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(final AdapterView<?> parent, final View view,
                                       final int position, final long id) {
                params.styleId = CmfPhotoWatchface.STYLE_IDS[position];
                onParamsChanged();
            }

            @Override
            public void onNothingSelected(final AdapterView<?> parent) {
                // keep the current style
            }
        });
        return spinner;
    }

    private Spinner colorSpinner() {
        final Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                CmfPhotoWatchface.COLOR_LABELS));
        spinner.setSelection(indexOf(CmfPhotoWatchface.COLOR_ARGB, params.colorArgb));
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(final AdapterView<?> parent, final View view,
                                       final int position, final long id) {
                params.colorArgb = CmfPhotoWatchface.COLOR_ARGB[position];
                onParamsChanged();
            }

            @Override
            public void onNothingSelected(final AdapterView<?> parent) {
                // keep the current colour
            }
        });
        return spinner;
    }

    private interface SliderListener {
        void onValue(int value);
    }

    private SeekBar slider(final int max, final int value, final SliderListener listener) {
        final SeekBar bar = new SeekBar(this);
        bar.setMax(max);
        bar.setProgress(Math.min(value, max));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(final SeekBar seekBar, final int progress,
                                         final boolean fromUser) {
                listener.onValue(progress);
            }

            @Override
            public void onStartTrackingTouch(final SeekBar seekBar) {
                // nothing to do
            }

            @Override
            public void onStopTrackingTouch(final SeekBar seekBar) {
                // nothing to do
            }
        });
        return bar;
    }

    private void onParamsChanged() {
        prefs.setParams(params);
        updatePreview();
    }

    private void pickImage() {
        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_PICK_IMAGE);
    }

    private void pickWatchfaceToInspect() {
        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_PICK_WATCHFACE);
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode,
                                    final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        final Uri uri = data.getData();

        if (requestCode == REQUEST_PICK_IMAGE) {
            loadSourceImage(uri);
        } else if (requestCode == REQUEST_PICK_WATCHFACE) {
            inspectFile(uri);
        }
    }

    private void loadSourceImage(final Uri uri) {
        setBusy(true, "Чтение фото…");

        new Thread(new Runnable() {
            @Override
            public void run() {
                Bitmap bitmap = null;
                String error = null;
                try {
                    bitmap = decodeBitmap(uri);
                } catch (final IOException | RuntimeException e) {
                    LOG.error("Failed to decode {}", uri, e);
                    error = describeError(e);
                } catch (final OutOfMemoryError e) {
                    LOG.error("Out of memory while decoding {}", uri, e);
                    error = describeError(e);
                }

                final Bitmap decoded = bitmap;
                final String failure = error;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        setBusy(false, null);
                        if (decoded == null) {
                            statusView.setText("Фото не прочитано: " + failure);
                            return;
                        }
                        if (sourceBitmap != null) {
                            sourceBitmap.recycle();
                        }
                        sourceBitmap = decoded;
                        builtFile = null;
                        sendButton.setEnabled(false);
                        statusView.setText("Фото готово: " + decoded.getWidth() + "×" + decoded.getHeight());
                        updatePreview();
                    }
                });
            }
        }, "cmf-watchface-decode").start();
    }

    private Bitmap decodeBitmap(final Uri uri) throws IOException {
        final byte[] raw = readAll(uri);

        final BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);

        int sample = 1;
        while (bounds.outWidth / sample > MAX_DECODE_SIZE
                || bounds.outHeight / sample > MAX_DECODE_SIZE) {
            sample *= 2;
        }

        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;

        final Bitmap bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.length, options);
        if (bitmap == null) {
            throw new IOException("Формат изображения не поддерживается");
        }
        return bitmap;
    }

    private byte[] readAll(final Uri uri) throws IOException {
        try (final InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IOException("Файл не открывается");
            }
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    /** Draws the photo at screen scale with the clock overlay and the hidden area marked. */
    private void updatePreview() {
        final int size = CmfPhotoWatchface.FULL_WIDTH;
        final Bitmap preview = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(preview);
        canvas.drawColor(Color.BLACK);

        if (sourceBitmap != null) {
            final Bitmap scaled = CmfPhotoWatchface.scaleCenterCrop(sourceBitmap, size, size);
            canvas.drawBitmap(scaled, 0, 0, null);
            scaled.recycle();
        }

        final Paint shade = new Paint();
        shade.setColor(Color.argb(120, 0, 0, 0));
        canvas.drawRect(0, CmfPhotoWatchface.VISIBLE_HEIGHT, size, size, shade);

        final Paint line = new Paint();
        line.setColor(Color.argb(200, 255, 255, 255));
        line.setStrokeWidth(2);
        canvas.drawLine(0, CmfPhotoWatchface.VISIBLE_HEIGHT, size,
                CmfPhotoWatchface.VISIBLE_HEIGHT, line);

        final Paint clock = new Paint(Paint.ANTI_ALIAS_FLAG);
        clock.setColor(params.colorArgb);
        clock.setTextAlign(Paint.Align.CENTER);
        clock.setTypeface(Typeface.create(Typeface.SANS_SERIF,
                params.styleId == 1 ? Typeface.NORMAL : Typeface.BOLD));
        clock.setTextSize(styleTextSize(params.styleId));
        canvas.drawText("10:24", params.positionX, params.positionY, clock);

        if (params.styleId == 2) {
            clock.setTextSize(styleTextSize(params.styleId) / 3f);
            canvas.drawText("СР 9 СЕН", params.positionX,
                    params.positionY + styleTextSize(params.styleId) / 2.5f, clock);
        }

        previewView.setImageBitmap(preview);
    }

    private float styleTextSize(final int styleId) {
        switch (styleId) {
            case 1:
                return 110f;
            case 2:
                return 100f;
            case 3:
                return 80f;
            default:
                return 130f;
        }
    }

    private void buildWatchface() {
        if (sourceBitmap == null) {
            Toast.makeText(this, "Сначала выберите фото", Toast.LENGTH_SHORT).show();
            return;
        }
        if (busy) {
            return;
        }

        setBusy(true, "Сборка… сжатие двух кадров занимает несколько секунд");
        prefs.setParams(params);

        new Thread(new Runnable() {
            @Override
            public void run() {
                String report;
                File output = null;
                try {
                    final CmfPhotoWatchface.Result result =
                            CmfPhotoWatchface.build(sourceBitmap, params);
                    output = writeToDisk(result.file);
                    report = result.report + "\nфайл=" + output.getName();
                } catch (final IOException | RuntimeException e) {
                    LOG.error("Failed to build watchface", e);
                    report = "Ошибка сборки: " + describeError(e);
                } catch (final OutOfMemoryError e) {
                    LOG.error("Out of memory while building the watchface", e);
                    report = "Не хватило памяти: " + describeError(e);
                }

                final String finalReport = report;
                final File finalOutput = output;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        setBusy(false, null);
                        reportView.setText(finalReport);
                        builtFile = finalOutput;
                        sendButton.setEnabled(finalOutput != null);
                        if (finalOutput != null) {
                            prefs.setLastFile(finalOutput.getAbsolutePath());
                            statusView.setText("Файл собран: " + finalOutput.getName());
                        } else {
                            statusView.setText("Сборка не удалась");
                        }
                    }
                });
            }
        }, "cmf-watchface-build").start();
    }

    private File writeToDisk(final byte[] data) throws IOException {
        final File base = getExternalFilesDir(null);
        if (base == null) {
            throw new IOException("Внешняя память недоступна");
        }

        final File dir = new File(base, OUTPUT_DIR_NAME);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Не создаётся папка " + dir);
        }

        final String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)
                .format(new Date());
        final File file = new File(dir, FILE_PREFIX + stamp + FILE_SUFFIX);

        try (final FileOutputStream out = new FileOutputStream(file)) {
            out.write(data);
            out.flush();
        }

        return file;
    }

    /**
     * Hands the built file to the Gadgetbridge install flow.
     *
     * <p>The file is shared through a content URI, because Android forbids passing file URIs
     * between components.</p>
     *
     * <p>The install screen needs the target device in the intent. Without the extra it shows
     * "No device provided to FwAppInstallerActivity" and closes. So the target is resolved here:
     * every known device is asked whether its coordinator accepts this file, which selects the
     * CMF watch and skips unrelated gadgets. Connected devices come first, and the user is asked
     * only when more than one device accepts the file.</p>
     */
    private void sendToWatch() {
        if (builtFile == null || !builtFile.exists()) {
            Toast.makeText(this, "Сначала соберите файл", Toast.LENGTH_SHORT).show();
            return;
        }

        final Uri uri;
        try {
            uri = FileProvider.getUriForFile(this, getPackageName() + FILE_PROVIDER_SUFFIX,
                    builtFile);
        } catch (final IllegalArgumentException e) {
            LOG.error("Failed to share {}", builtFile, e);
            statusView.setText("Файл не передаётся: " + describeError(e));
            return;
        }

        final List<GBDevice> known = knownDevices();
        final List<GBDevice> targets = acceptingDevices(known, uri);

        if (targets.isEmpty()) {
            statusView.setText(known.isEmpty()
                    ? "Устройство не найдено. Добавьте часы в Gadgetbridge и повторите."
                    : "Ни одно устройство не принимает этот файл. Нужны часы CMF Watch Pro 2 или Pro 3.");
            return;
        }

        if (targets.size() == 1) {
            startInstaller(uri, targets.get(0));
            return;
        }

        askWhichDevice(uri, targets);
    }

    /** Returns every device Gadgetbridge knows about, or an empty list before startup finished. */
    private List<GBDevice> knownDevices() {
        final GBApplication application = GBApplication.app();
        if (application == null) {
            LOG.warn("Application is not ready yet");
            return new ArrayList<>();
        }

        final DeviceManager manager = application.getDeviceManager();
        if (manager == null) {
            LOG.warn("Device manager is not ready yet");
            return new ArrayList<>();
        }

        return new ArrayList<>(manager.getDevices());
    }

    /**
     * Keeps the devices that accept the file, most ready device first.
     *
     * <p>The question is answered by the same call the install screen makes later, so a device
     * that passes here will also find its install handler there.</p>
     */
    private List<GBDevice> acceptingDevices(final List<GBDevice> devices, final Uri uri) {
        final List<GBDevice> accepting = new ArrayList<>();

        for (final GBDevice device : devices) {
            try {
                if (device.getDeviceCoordinator().findInstallHandler(uri, Bundle.EMPTY, this) != null) {
                    accepting.add(device);
                }
            } catch (final RuntimeException e) {
                LOG.warn("Device {} could not be asked about the file", device, e);
            }
        }

        Collections.sort(accepting, new Comparator<GBDevice>() {
            @Override
            public int compare(final GBDevice left, final GBDevice right) {
                return readiness(right) - readiness(left);
            }
        });

        return accepting;
    }

    /** A connected watch is a better default target than a paired but idle one. */
    private static int readiness(final GBDevice device) {
        if (device.isInitialized()) {
            return 2;
        }
        if (device.isConnected()) {
            return 1;
        }
        return 0;
    }

    private void askWhichDevice(final Uri uri, final List<GBDevice> targets) {
        final String[] labels = new String[targets.size()];
        for (int i = 0; i < targets.size(); i++) {
            labels[i] = targets.get(i).getAliasOrName();
        }

        new AlertDialog.Builder(this)
                .setTitle("Куда отправить")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(final DialogInterface dialog, final int which) {
                        startInstaller(uri, targets.get(which));
                    }
                })
                .show();
    }

    private void startInstaller(final Uri uri, final GBDevice device) {
        final Intent intent = new Intent(this, FwAppInstallerActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/octet-stream");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.putExtra(GBDevice.EXTRA_DEVICE, device);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            startActivity(intent);
            statusView.setText("Экран установки открыт: " + device.getAliasOrName());
        } catch (final RuntimeException e) {
            LOG.error("Failed to open the install screen", e);
            statusView.setText("Экран установки не открылся: " + describeError(e));
        }
    }

    private void inspectFile(final Uri uri) {
        setBusy(true, "Разбор файла…");

        new Thread(new Runnable() {
            @Override
            public void run() {
                String report;
                try {
                    report = CmfPhotoWatchface.describe(readAll(uri));
                } catch (final IOException | RuntimeException e) {
                    LOG.error("Failed to inspect {}", uri, e);
                    report = "Файл не прочитан: " + describeError(e);
                }

                final String finalReport = report;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        setBusy(false, null);
                        reportView.setText(finalReport);
                        statusView.setText("Разбор завершён");
                    }
                });
            }
        }, "cmf-watchface-inspect").start();
    }

    /**
     * Formats a failure for the screen.
     *
     * <p>Several exceptions carry no message at all. {@link java.nio.BufferOverflowException} is
     * the classic one, and Android does not add helpful null pointer messages either. Printing
     * only {@code getMessage()} then shows the bare word null, which says nothing about what
     * failed. The class name is always available, so it is always shown.</p>
     */
    private static String describeError(final Throwable e) {
        final String message = e.getMessage();
        if (message == null || message.isEmpty()) {
            return e.getClass().getSimpleName() + " (без текста, см. logcat)";
        }
        return message + " [" + e.getClass().getSimpleName() + "]";
    }

    private void restoreLastFile() {
        final String path = prefs.getLastFile();
        if (path == null) {
            return;
        }

        final File file = new File(path);
        if (!file.exists()) {
            return;
        }

        builtFile = file;
        sendButton.setEnabled(true);
        statusView.setText("Готов предыдущий файл: " + file.getName());
    }

    private void setBusy(final boolean value, final String message) {
        busy = value;
        buildButton.setEnabled(!value);
        if (message != null) {
            statusView.setText(message);
        }
    }

    private TextView header(final String text) {
        final TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(18);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(16), 0, dp(4));
        return view;
    }

    private TextView body(final String text) {
        final TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(14);
        view.setPadding(0, dp(4), 0, dp(4));
        return view;
    }

    private Button button(final String text, final View.OnClickListener listener) {
        final Button view = new Button(this);
        view.setText(text);
        view.setAllCaps(false);
        view.setOnClickListener(listener);
        return view;
    }

    private int dp(final int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int indexOf(final int[] values, final int value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == value) {
                return i;
            }
        }
        return 0;
    }

    @Override
    protected void onDestroy() {
        if (sourceBitmap != null) {
            sourceBitmap.recycle();
            sourceBitmap = null;
        }
        super.onDestroy();
    }
}
