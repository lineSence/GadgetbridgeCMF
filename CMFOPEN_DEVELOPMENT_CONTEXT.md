# CMFOPEN — GadgetbridgeCMF development context

> **Purpose:** handoff document for another coding/research agent working on the CMF Watch Pro 2 open-source reverse-engineering effort and the `GadgetbridgeCMF` fork.
>
> **Status date:** 2026-09-09.
>
> **Repository:** `lineSence/GadgetbridgeCMF`
>
> **Active development branch:** `feature/cmf-p0`
>
> **Related reverse-engineering repository:** `lineSence/CMF-Watch-Pro-2-Open`

---

## 1. Project goal

The project is an open-source effort to understand and extend support for **CMF Watch Pro 2** in Gadgetbridge. The work combines:

1. protocol reverse engineering;
2. validation against captured device traffic and known protocol layouts;
3. production integration into the Gadgetbridge device support;
4. deterministic unit tests for binary protocol encoders/decoders;
5. a small set of protocol smoke tests exposed through Gadgetbridge's **existing Debug Activity**;
6. CI verification and debug APK builds.

The project should prefer verified protocol facts over guesses. If a packet format is not established by RE evidence, do not invent an implementation merely to make a feature appear complete.

The user strongly prefers **ready-to-use, complete code** and expects changes to be actually committed and build-verified.

---

## 2. Repository architecture / important locations

### Gadgetbridge fork

`lineSence/GadgetbridgeCMF`

The fork was synchronized to approximately Gadgetbridge **0.93.0** from an older 0.83.0-based fork.

Important baseline commit previously identified:

`a09e037374d0013ffc31978fc1d1ada2943280e4`

Current P0 branch head at the time this document was created:

`e935b8112058737cfcbeccb144efd24ae582cb01`

Latest head commit message:

`CI: run CMF P0 build on pull requests too`

### CMF implementation package

`app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/cmfwatchpro/`

Important classes:

- `CmfWatchProSupport.java` — main device transport/support implementation.
- `CmfCharacteristic.java` — characteristic transport and packet handling; existing raw RX logging is already present here.
- `CmfCommand.java` — CMF command identifiers.
- `CmfPreferences.java` — preference synchronization and related protocol payloads.
- `CmfActivitySync.java` — activity/sleep synchronization.
- `CmfWatchProCoordinator.java` — coordinator/device metadata and capabilities.
- `CmfProtocolUtils.java` — deterministic binary payload builders added for P0.
- `CmfProtocolUtilsTest.java` — unit tests for protocol builders.

### Existing Gadgetbridge debug infrastructure

Do **not** create a second/general-purpose debug subsystem.

Existing infrastructure:

- `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/activities/debug/DebugActivityV2.kt`
- `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/activities/debug/MainDebugFragment.kt`
- `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/activities/debug/AbstractDebugFragment.kt`
- `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/activities/debug/DeviceDebugFragment.kt`
- `app/src/main/res/xml/debug_preferences_main.xml`
- `app/src/main/res/xml/debug_preferences_device.xml`

`AbstractDebugFragment` already supplies `runOnDebugDevices(...)`, dynamic preferences/categories, and navigation helpers. `MainDebugFragment` owns the global Debug Activity and existing log-sharing/file-logging functionality.

The CMFOPEN debug actions are integrated into this existing Debug Activity through:

- `CmfOpenDebugFragment.kt`
- `debug_preferences_cmfopen.xml`
- an entry in `debug_preferences_main.xml`.

Current CMFOPEN UI is deliberately marked with the literal **CMFOPEN** prefix.

---

## 3. Non-negotiable CMFOPEN development rules

### 3.1 Mark all new RE features

Every new feature, test, debug action, or log specifically introduced for the CMF reverse-engineering effort should be visibly or source-level marked:

`CMFOPEN`

Examples:

- `CMFOPEN — Test TIME`
- `CMFOPEN — Test CONTACTS`
- `CMFOPEN — Test ALARMS`
- `CMFOPEN — Test ACTIVITY`
- `[CMFOPEN] ...` in new protocol-specific log messages.

Do not silently add CMF RE functionality without the marker.

### 3.2 Reuse existing debug facilities

Do not add another global debug screen, duplicate packet logger, or parallel test framework if Gadgetbridge already provides the required mechanism.

The existing `CmfCharacteristic` already handles raw received-data logging via Gadgetbridge's normal logger. Reuse that logging path rather than implementing another RX/TX logger.

### 3.3 Do not guess protocol layouts

Only implement packet layouts that are supported by RE evidence or existing validated code.

When a field is uncertain, document it as uncertain and avoid encoding assumptions into production synchronization.

### 3.4 Build verification is mandatory

After meaningful code changes:

1. run unit tests;
2. build a debug APK;
3. inspect CI result;
4. report the exact commit and workflow/run status.

Never claim that a build passed unless the build job actually completed successfully.

---

## 4. Confirmed protocol facts

These are the currently established facts from the CMF Watch Pro 2 RE work.

### 4.1 Contacts

Command:

`0x00D5 0001`

Each contact record is **57 bytes**:

- name: 32 bytes
- phone: 25 bytes

Maximum currently supported/capped count:

`20`

Production encoding is centralized in:

`CmfProtocolUtils.buildContactsPayload(...)`

### 4.2 Alarms

Command:

`0x0063 0001`

Each alarm record is **40 bytes**:

| Offset | Size | Field |
|---:|---:|---|
| 0 | 4 | `secondsOfDay`, signed/int32, big-endian |
| 4 | 1 | `index` |
| 5 | 1 | `enabled` |
| 6 | 1 | `repetition` |
| 7 | 1 | `flag` |
| 8 | 32 | UTF-8 label |

The label offset is **byte 8**, not byte 0/another legacy location.

This corrected an older Gadgetbridge encoding assumption.

Production encoding is centralized in:

`CmfProtocolUtils.buildAlarmsPayload(...)`

The protocol utility test explicitly verifies label placement at byte 8 and zero-padding through byte 39.

The coordinator alarm title limit was also corrected from **8 to 32**.

### 4.3 Goals

Command:

`0x005E 0001`

Payload is exactly **10 bytes**, big-endian:

`steps(u32) | distance_m(u32) | calories_kcal(u16)`

Production encoding is centralized in:

`CmfProtocolUtils.buildGoalsPayload(...)`

### 4.4 Units

Known commands:

- `UNIT_LENGTH = 0xFFFF 9067`
- `UNIT_TEMPERATURE = 0xFFFF 9068`

### 4.5 Standing / hydration reminders

Known payload length: **11 bytes**.

Layout:

- enabled: 1 byte
- threshold minutes: `u16 LE`
- DND start: `u32 LE`
- DND end: `u32 LE`

A previous production preference bug where inactivity reminder handling fell through into hydration handling was fixed by adding the missing `break`.

### 4.6 Weather

Set command:

`0xFFFF 906B`

Payload length:

`199 bytes`

Known structure:

- 7 × 9-byte daily records
- 24 × 2-byte hourly records
- city: 32 bytes
- 7 × 8-byte sunrise/sunset information

Do not change field widths without new RE evidence.

### 4.7 TIME ordering requirement

Command:

`0xFFFF 8004`

The watch requires TIME to be sent **before** battery / serial / activity GET requests in the relevant initialization/synchronization flow.

P0 also introduced a per-device timestamp preference:

`cmf_last_time_sent`

with a roughly **120-second tolerance** to avoid redundant time sends.

There is also a backwards-time guard in the production support logic.

### 4.8 GET → SET / ACK pattern

Observed protocol behavior includes GET → SET echo patterns.

SET acknowledgement command:

`cmd2 = 0x0003`

Treat this as a known protocol pattern, but do not assume every command has identical semantics without evidence.

---

## 5. Existing P0 production changes

### `CmfProtocolUtils.java`

Added constants:

- `MAX_CONTACTS = 20`
- `CONTACT_RECORD_SIZE = 57`
- `ALARM_RECORD_SIZE = 40`
- `ALARM_LABEL_SIZE = 32`

Added/centralized deterministic builders:

- `buildGoalsPayload(...)`
- `buildContactsPayload(...)`
- `buildAlarmsPayload(...)`

Also contains:

`shouldSendTime(now,lastSent)`

with a 120-second tolerance.

Previously recorded production blob SHA:

`c89f4457cacae5b158d60eb39f89e416a7cb8ee9`

### `CmfPreferences.java`

Changes include:

- fixed missing `break` after inactivity reminder handling;
- goals encoding now goes through `CmfProtocolUtils.buildGoalsPayload(...)`.

### `CmfWatchProSupport.java`

P0 changes include:

- `cmf_last_time_sent` preference;
- contacts use protocol utility and cap at 20;
- backwards-time guard per device;
- same time guard during phase-2 initialization;
- alarms use protocol utility;
- `onSetTime()` records last sent timestamp and returns a boolean.

Important: when continuing development, inspect the current branch rather than relying only on this document, because later commits may alter these details.

### `CmfActivitySync.java`

Added validation that:

- sleep payload length is at least 18;
- `(length - 18) % 8 == 0`.

Before saving a sleep session, an existing session with the same start is checked. If an existing session has a wakeup time greater than or equal to the incoming one, the incoming duplicate is skipped.

This is a **partial** duplicate fix: a newer same-start session may still duplicate stage rows. Do not assume complete deduplication has been solved.

### `CmfWatchProCoordinator.java`

Alarm title limit changed:

`8 → 32`

---

## 6. Unit tests

`CmfProtocolUtilsTest.java` exists and covers the deterministic P0 protocol encoders.

Important alarm assertions include:

```text
payload[8]  == 'W'
payload[9]  == 'a'
payload[14] == 'p'
payload[15] == 0
payload[39] == 0
```

A previous test expected the label at the wrong offset and was corrected in commit:

`57032dd453d9ee1c4055ae6a17a0488a5cbf5e32`

Do not regress the byte-8 alarm label offset.

---

## 7. CMFOPEN debug tests currently integrated

File:

`app/src/main/java/nodomain/freeyourgadget/gadgetbridge/activities/debug/CmfOpenDebugFragment.kt`

UI resource:

`app/src/main/res/xml/debug_preferences_cmfopen.xml`

Main Debug Activity registration:

`app/src/main/res/xml/debug_preferences_main.xml`

Current smoke tests:

### `CMFOPEN — Test TIME`

Uses the existing device-service `onSetTime()` path.

### `CMFOPEN — Test CONTACTS`

Uses the normal `onSetContacts(...)` path with one deterministic contact:

- name: `CMFOPEN TEST`
- number: `+10000000000`

### `CMFOPEN — Test ALARMS`

Uses normal `onSetAlarms(...)` with one deterministic alarm:

- position: 0
- enabled: true
- time: 12:34
- title: `CMFOPEN TEST ALARM`
- repetition: once

### `CMFOPEN — Test ACTIVITY`

Uses the existing:

`onFetchRecordedData(RecordedDataTypes.TYPE_ACTIVITY)`

path rather than implementing a second activity transport path.

### Device restriction

The CMFOPEN fragment uses the existing `runOnDebugDevices(...)` helper and only executes the tests for devices whose coordinator is `CmfWatchProCoordinator`.

If another device is selected, it shows a warning rather than attempting a CMF packet against it.

---

## 8. Existing logging / transport that must be reused

`CmfCharacteristic.java` already logs raw received packet data through Gadgetbridge's logger, including the packet length and `GB.hexdump(value)`.

`AbstractBTLESingleDeviceSupport` provides the normal Gadgetbridge transport infrastructure, including mechanisms such as:

- `createTransactionBuilder(...)`
- `performInitialized(...)`
- `getQueue()`
- `getCharacteristic(...)`

`CmfWatchProSupport` owns the characteristic instances and the authenticated CMF transport.

Therefore, a future CMFOPEN command test should normally be implemented by calling the existing support/device-service path rather than manually creating another BLE transport layer.

---

## 9. CI / build system

Workflow:

`.github/workflows/cmf-p0-build.yml`

Current intended workflow:

```yaml
name: CMF P0 build

on:
  push:
    branches:
      - feature/cmf-p0
  pull_request:
    branches:
      - master
  workflow_dispatch:

permissions:
  contents: read

jobs:
  build-and-test:
    runs-on: ubuntu-latest
    timeout-minutes: 45
    steps:
      - name: Checkout
        uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Set up Java 21
        uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: '21'
          cache: gradle

      - name: Make Gradle wrapper executable
        run: chmod +x ./gradlew

      - name: Verify Gradle wrapper
        run: ./gradlew --version

      - name: Run CMF P0 unit tests
        run: ./gradlew :app:testMainlineDebugUnitTest --no-daemon --stacktrace

      - name: Build debug APK
        run: ./gradlew assembleDebug --no-daemon --stacktrace

      - name: Upload debug APK
        uses: actions/upload-artifact@v4
        with:
          name: GadgetbridgeCMF-P0-debug-apk
          path: app/build/outputs/apk/**/*.apk
          if-no-files-found: error
          retention-days: 14
```

### Verified successful build

Workflow run:

`34318228766`

Job:

`102358907052`

All important steps completed successfully:

- checkout — PASS
- Java 21 — PASS
- Gradle wrapper verification — PASS
- CMF P0 unit tests — **PASS**
- debug APK build — **PASS**
- artifact upload — **PASS**

Artifact:

`GadgetbridgeCMF-P0-debug-apk`

Artifact size:

`57,492,124` bytes

SHA-256:

`f78f5faaa505dbb4eb62d0618dd9cab973eea5885bf67cb44aacef2c98b89869`

Artifact expiration reported by GitHub:

2026-09-23.

This confirms that the branch head used for that run compiled and passed the configured P0 unit test suite.

---

## 10. Relevant history / previous CI issue

An earlier workflow run checked out an older SHA and failed an alarm unit test because the test expected the label at the wrong byte offset.

The corrected test commit was:

`57032dd453d9ee1c4055ae6a17a0488a5cbf5e32`

The workflow itself was later corrected to include proper `jobs` structure and then extended to run on pull requests.

Do not use the older failed run as evidence that the current branch is broken; the current verified run is `34318228766` and is successful.

---

## 11. RE project: `CMF-Watch-Pro-2-Open`

There is a separate repository:

`https://github.com/lineSence/CMF-Watch-Pro-2-Open`

It is the canonical place for protocol reverse-engineering material, captures, findings, and related investigation where applicable.

The Gadgetbridge fork should consume **validated** protocol findings from the RE effort. Keep exploratory/uncertain observations separate from production protocol assumptions.

The project has investigated, among other things:

- command identifiers;
- GET/SET behavior;
- packet sizes and field offsets;
- authentication/session-key behavior;
- time synchronization ordering;
- activity/sleep records;
- contacts;
- alarms;
- goals;
- units;
- reminders;
- weather;
- characteristic structure;
- raw BLE packet traffic.

The CMF device exposes several relevant BLE services/characteristics in `CmfWatchProSupport`.

Known service UUIDs currently in the implementation include:

- command service: `0000fff0-0000-1000-8000-00805f9b34fb`
- command read: `0000fff1-0000-1000-8000-00805f9b34fb`
- command write: `0000fff2-0000-1000-8000-00805f9b34fb`
- data service: `02f00000-0000-0000-0000-00000000ffe0`
- data write: `02f00000-0000-0000-0000-00000000ffe1`
- data read: `02f00000-0000-0000-0000-00000000ffe2`
- shell service: `77d4e67c-2fe2-2334-0d35-9ccd078f529c`
- shell write: `77d4ff01-2fe2-2334-0d35-9ccd078f529c`
- shell read: `77d4ff02-2fe2-2334-0d35-9ccd078f529c`
- firmware service: `02f00000-0000-0000-0000-00000000fe00`
- firmware write: `02f00000-0000-0000-0000-00000000ff01`
- firmware read: `02f00000-0000-0000-0000-00000000ff02`

`CmfWatchProSupport` uses an `A5` byte frequently in authentication/single-payload contexts. Existing source comments describe it as probably related to proof/encryption; this should **not** be promoted to a confirmed semantic without further evidence.

---

## 12. Authentication notes

`CmfWatchProSupport` contains the existing authentication/session negotiation.

Important high-level flow already present:

1. initialize CMF characteristics;
2. obtain stored secret key where available;
3. otherwise use shell/authentication path to obtain/establish key material;
4. exchange authentication values;
5. derive session key using SHA-256-based logic;
6. install the session key into CMF characteristics;
7. continue normal command traffic.

Do not bypass this with raw unauthenticated writes in production code.

For CMFOPEN debug actions, prefer calling the normal initialized service path so authentication/session state is handled exactly as production code handles it.

---

## 13. Current debug design decision

A prior investigation found multiple existing Gadgetbridge debug tools. The important conclusion was:

**CMFOPEN functionality should be integrated into the existing Debug Activity rather than creating a duplicate CMF-specific debug application/panel.**

Current implementation follows that decision.

`MainDebugFragment` remains the normal global debug screen and continues to provide existing log file functionality. `CmfOpenDebugFragment` is a specialized child fragment registered in that screen.

Raw packet logging remains in the existing CMF characteristic path.

This architecture should be maintained unless a future design review establishes a concrete reason otherwise.

---

## 14. Known limitations / TODOs

These are areas where an agent should be careful rather than assuming completion:

### Activity/sleep deduplication

The current sleep-session deduplication only checks the session-level start/wakeup information. A newer same-start session may still result in duplicate stage data. A robust stage-level deduplication strategy should be based on actual DB schema/record semantics, not guessed keys.

### More protocol smoke tests

The current CMFOPEN debug panel covers the P0 paths that have sufficiently established production APIs:

- TIME
- CONTACTS
- ALARMS
- ACTIVITY

Additional tests can be added only when the packet format and intended behavior are sufficiently verified.

Potential areas for later work include goals, units, reminders, weather, battery/serial requests, and raw command inspection — but these should be integrated through existing support methods and marked `CMFOPEN`.

### On-device validation

CI proves compilation/unit tests/APK generation, but it does **not** prove every CMFOPEN operation works against a physical watch. Physical-device validation requires actual BLE communication and log/capture inspection.

The user has previously reported that a successfully built APK works on the device; however, each newly added protocol operation still needs appropriate device-side validation.

---

## 15. Agent workflow checklist

When continuing this project:

1. **Inspect the current branch first.** Do not assume this document is newer than the repository.
2. Read `CmfWatchProSupport`, `CmfCharacteristic`, `CmfCommand`, `CmfProtocolUtils`, and the relevant tests before changing protocol behavior.
3. Check the RE repository for evidence before adding a new packet format.
4. Reuse existing Gadgetbridge transport and debug infrastructure.
5. Mark every new CMF RE feature with `CMFOPEN`.
6. Prefer deterministic protocol utility methods for binary layouts.
7. Add/extend unit tests for every deterministic encoder/decoder.
8. Avoid reflection if a normal public/device-service path can be used.
9. Do not create a second packet logger if existing CMF characteristic logging is sufficient.
10. Run the CMF P0 unit test task.
11. Run the debug APK build.
12. Verify the GitHub Actions result before reporting success.
13. For protocol changes, distinguish clearly between **confirmed**, **strongly suspected**, and **unverified** behavior.
14. If a test operation can alter watch state (contacts, alarms, goals, etc.), keep its deterministic nature explicit in the UI and logs.

---

## 16. Current state summary

At the handoff point:

- Gadgetbridge fork: active and buildable.
- CMF Watch Pro 2 P0 protocol support: implemented for the verified P0 areas.
- Binary payload helpers: centralized in `CmfProtocolUtils`.
- Unit tests: present and passing in the latest verified CI run.
- Existing Gadgetbridge Debug Activity: reused.
- CMFOPEN debug smoke-test fragment: integrated.
- New CMFOPEN UI labels: explicitly marked.
- Existing raw CMF packet logging: reused.
- CI debug APK: successfully built.
- Latest verified branch head: `e935b8112058737cfcbeccb144efd24ae582cb01`.
- Latest verified workflow: `34318228766`.

**Do not treat this document as a substitute for the current source tree. It is a context/handoff guide. Source code and the RE repository remain authoritative.**
