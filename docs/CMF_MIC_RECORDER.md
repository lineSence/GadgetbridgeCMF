# CMF диктофон — PoC записи через микрофон часов (SCO)

Контекст и теория — в [`CMF_CAPABILITIES.md`](CMF_CAPABILITIES.md), раздел 3.1.

Цель PoC: **проверить на живом устройстве, даёт ли CMF Watch Pro 2 поднять SCO-канал вне звонка** и пишется ли реальный звук с её микрофона (а не тишина и не микрофон телефона). Прошивка часов не затрагивается.

---

## 1. Состав

| Файл | Роль |
|------|------|
| `CmfScoAudioLink.java` | поднятие/освобождение SCO: `setCommunicationDevice` (API 31+) или `startBluetoothSco` + `ACTION_SCO_AUDIO_STATE_UPDATED` |
| `CmfMicRecorder.java` | рабочий поток `AudioRecord` → WAV, автовыбор 16000/8000 Гц, измерение RMS/peak, режим self-test |
| `CmfWavWriter.java` | чистый Java-запись RIFF/WAVE с патчем длин при закрытии |
| `CmfRecorderStatus.java` | форматирование `REC ● 00:42` для экрана часов и шторки |
| `CmfMicRecorderService.java` | foreground-сервис (`microphone`) + `MediaSession` для кнопок часов |
| `app/src/debug/AndroidManifest.xml` | разрешения и регистрация сервиса **только в debug-сборке** |

Пакет: `nodomain.freeyourgadget.gadgetbridge.service.devices.cmfwatchpro.recorder`

Ни один существующий файл проекта не изменён, основной `AndroidManifest.xml` не тронут: сервис и разрешение `RECORD_AUDIO` попадают только в debug-вариант через merge источникового набора `debug`. Релиз-сборка остаётся без новых разрешений.

---

## 2. Сборка и установка

APK собирает CI (`CMF P0 build` → артефакт `GadgetbridgeCMF-P0-debug-apk`) или локально:

```bash
./gradlew :app:testMainlineDebugUnitTest
./gradlew assembleMainlineDebug
adb install -r app/build/outputs/apk/mainline/debug/*.apk
```

Определить имя пакета установленной сборки:

```bash
adb shell pm list packages | grep -i gadgetbridge
# далее подставляйте его вместо $PKG
PKG=nodomain.freeyourgadget.gadgetbridge
```

Выдать разрешения (или вручную в настройках приложения):

```bash
adb shell pm grant $PKG android.permission.RECORD_AUDIO
adb shell pm grant $PKG android.permission.BLUETOOTH_CONNECT
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
```

Перед тестом: часы должны быть **спарены как Bluetooth-гарнитура** (не только BLE!) и в статусе «подключено» в настройках Bluetooth телефона. Без HFP-профиля SCO не поднимется вообще.

---

## 3. Запуск

Класс сервиса:

```
nodomain.freeyourgadget.gadgetbridge.service.devices.cmfwatchpro.recorder.CmfMicRecorderService
```

### 3.1 Самодиагностика (главный тест гипотезы)

Запись 10 с + текстовый отчёт:

```bash
adb shell am start-foreground-service \
  -n $PKG/nodomain.freeyourgadget.gadgetbridge.service.devices.cmfwatchpro.recorder.CmfMicRecorderService \
  -a nodomain.freeyourgadget.gadgetbridge.cmf.recorder.SELFTEST \
  --ei duration_seconds 10
```

Говорите в часы, а не в телефон (телефон лучше отложить метра на два) — так сразу видно, чей микрофон попал в запись.

### 3.2 Свободная запись

```bash
# старт
adb shell am start-foreground-service -n $PKG/….CmfMicRecorderService \
  -a nodomain.freeyourgadget.gadgetbridge.cmf.recorder.START
# стоп
adb shell am start-foreground-service -n $PKG/….CmfMicRecorderService \
  -a nodomain.freeyourgadget.gadgetbridge.cmf.recorder.STOP
```

Доступные action-ы: `START`, `STOP`, `TOGGLE`, `SELFTEST`.
Дополнительные extras: `--ei duration_seconds N`, `--ez use_phone_mic true` (контрольный прогон без SCO, чтобы сравнить звук).

### 3.3 Где результат

```bash
adb shell ls -l /sdcard/Android/data/$PKG/files/Music/
adb shell cat /sdcard/Android/data/$PKG/files/Music/cmf-rec-*.txt
adb pull /sdcard/Android/data/$PKG/files/Music/
```

Логи: `adb logcat -s CmfMicRecorder CmfScoAudioLink CmfMicRecorderService`

---

## 4. Как читать отчёт

```
scoOffCallSupported=true          # AudioManager.isBluetoothScoAvailableOffCall()
headsetConnected=true            # часы видны в профиле HEADSET
scoRoute=communicationDevice     # либо legacyStartBluetoothSco
scoConnectMillis=742             # сколько ждали канал
(sampleRate=16000, channels=1)   # 16000 = mSBC, 8000 = CVSD
durationMillis=10015
peak=0.71  rms=0.083  silentRatio=0.04
```

Интерпретация:

| Наблюдение | Вывод |
|------------|-------|
| `scoOffCallSupported=false` | платформа телефона запрещает SCO вне звонка → вариант 1 на этом телефоне невозможен |
| `headsetConnected=false` | часы подключены только по BLE → спарить как гарнитуру |
| канал не поднялся (`sco timeout`) | прошивка часов открывает SCO только на звонок → остаётся обход через фейковый звонок или вариант 2 |
| `silentRatio > 0.95` | канал есть, но звука нет → проверить `AudioSource`, громкость, право `RECORD_AUDIO` |
| звук есть, но это микрофон телефона | маршрутизация не применилась → см. `scoRoute` и лог `communicationDevice=…` |
| `sampleRate=8000` | кодек CVSD; для mSBC (16 кГц) нужен wideband на обоих концах |

---

## 5. Управление с часов

Сервис поднимает `MediaSession` с метаданными `REC ● mm:ss` / `CMF recorder`. Отсюда бесплатно получается:

1. **Индикатор на часах.** Gadgetbridge транслирует активную медиа-сессию в `MUSIC_INFO_SET (FFFF 905C)`, так что экран плеера показывает статус и таймер записи.
2. **Кнопки часов.** `MUSIC_BUTTON (FFFF A05D)` превращается в медиа-клавиши, которые приходят в нашу сессию: `play/pause → старт/стоп`, `next → метка`.

⚠️ Зависит от того, какая сессия активна в системе и включён ли доступ к уведомлениям. Детерминированный вариант — прямой хук в `CmfWatchProSupport.onCommand` (шаг P1):

```java
case MUSIC_BUTTON:
    if (CmfMicRecorderService.isRecordingSessionActive()) {
        CmfMicRecorderService.dispatch(getContext(),
                CmfMicRecorderService.ACTION_TOGGLE);
        return true;
    }
    break;
```

Плюс `AT SETMOTOR=1` для тактильного подтверждения старта/стопа.

---

## 6. Ограничения PoC

* качество — телефонное: моно 8/16 кГц, шумоподавление под речь;
* пока канал поднят, система считает что идёт «разговор»: прочее аудио приглушается, входящий звонок прерывает запись;
* без Opus/AAC — только WAV (16-bit PCM), ≈ 32 КБ/с на 16 кГц;
* без UI в приложении: управление через adb, шторку или кнопки часов;
* сервис объявлен `exported="true"` **только в debug-манифесте** ради adb-тестов; перед мержем в релиз перенести в основной манифест с `exported="false"`.

---

## 7. Дальше

1. Прогнать `SELFTEST` и приложить отчёт — от него зависит вся ветка работ. Затем обновить статус в `CMF_CAPABILITIES.md` §0.
2. Если SCO работает: Opus-кодирование, UI-экран списка записей, автостоп по тишине, транскрипция.
3. Если не работает: проверить сценарий с `INCOMING_CALL (0064 0001)` (часы сами откроют аудиоканал) либо уходить на уровень C.
