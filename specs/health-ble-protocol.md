# Health bridge BLE protocol (contract)

*Authoritative byte-level contract between apps/wearos and apps/ios. Implemented iOS-side in `apps/ios/AncsBridge/Health/HealthTLV.swift`; the watch side must match exactly. Tests in `apps/ios/AncsBridgeTests/HealthTLVTests.swift` pin the layout.*

All integers **little-endian**. All timestamps are **epoch milliseconds (u64)**.

## GATT

Service `A0E3D5B1-4C6F-4E8A-9B2D-7F1E3A5C8D0B` (companion service, iOS = GATT server, watch = client):

| Characteristic | UUID tail | Properties | Direction |
|---|---|---|---|
| health-control | `A0E3D5B5-…` | read, notify, write, write-without-response | watch writes HELLO/PING; iOS notifies ACK/TIME_SYNC/PONG |
| health-data | `A0E3D5B6-…` | write, write-without-response | watch writes batches (one complete batch per write) |

Batch size cap: negotiated ATT MTU − 3 (typically 509 bytes with MTU 512).

## Data characteristic framing

```
batch  = [u8 record_count] record+
record = [u8 type][u16 payload_len] payload
payload = [u64 record_id] type_specific_fields
```

`record_id` = Room autoincrement PK on the watch, **monotonic per app install**. Every record carries it — ACKs and iOS-side dedupe depend on it.

**`payload_len` = byte length of the full payload INCLUDING the `u64 record_id`** (e.g. an HR record: `payload_len` = 18). The byte counts in the table below describe the fields *after* `record_id` only.

### Record types

| Type | Value | Fields after record_id |
|---|---|---|
| HR | 1 | `bpm u16`, `start_ms u64` (10 B) |
| STEPS | 2 | `count u32`, `start_ms u64`, `end_ms u64` (20 B) — interval bucket, not whole day |
| SLEEP | 3 | `stage u8`, `start_ms u64`, `end_ms u64` (17 B) |
| SLEEP_SUMMARY | 4 | `start_ms u64`, `end_ms u64`, `stage_count u8`, then per stage: `stage u8`, `minutes u32` (fallback when no SLEEP records) |
| WORKOUT_HEADER | 5 | `activity u8`, `start_ms u64`, `end_ms u64`, `active_min u32`, `calories_tenths u16` (kcal × 10), `distance_m u32`, `avg_hr u16`, `max_hr u16`, `hr_record_count u16`, `gps_record_count u16` — sent AFTER the workout ends; the two counts are the number of following WORKOUT_HR_STREAM / WORKOUT_GPS *records* the watch will send for it |
| WORKOUT_HR_STREAM | 6 | `sample_count u8` (≤ 255), then per sample: `ts_ms u64`, `bpm u16` |
| WORKOUT_GPS | 7 | `point_count u8` (≤ 255), then per point: `lat i32` (deg × 1e7), `lng i32` (deg × 1e7), `alt_m f32` (IEEE 754), `ts_ms u64` |
| BATTERY | 8 | `percent u8`, `ts_ms u64` (display only; HealthKit has no battery type — still ACKed) |

### Value tables

Sleep stage: `0` awake · `1` light (=asleepCore) · `2` deep (=asleepDeep) · `3` REM · `4` in bed.

Workout activity: `0` running · `1` walking · `2` cycling · `3` swimming · `4` strength · `5` yoga · `6` hiking · `7` elliptical · `8` rowing · `9` HIIT · `10` dance · `11` boxing · `12` pilates · `13` gymnastics · `14` golf · `15` tennis · `16` basketball · `17` soccer · `18` rugby · `19` cricket · `20` skiing · `21` snowboarding · `22` skating · `23` climbing · other = `255` (watch maps unknown ExerciseTypes to 255, iOS maps 255+anything-unknown to `.other`).

## Control characteristic messages

```
HELLO         = 0x01, install_nonce u32, type_bitmap u8, cursor_record_id u64   (watch → iOS)
ACK           = 0x02, count u8, record_id u64 × count                           (iOS → watch)
TIME_SYNC     = 0x03, epoch_ms u64                                              (iOS → watch; also the B5 read value)
PING          = 0x04                                                            (watch → iOS)
PONG          = 0x05                                                            (iOS → watch)
TRIGGER_LIST  = 0x06, count u8, then per trigger: index u8, kind u8, len u16, payload utf8   (iOS → watch)
TRIGGER_FIRE  = 0x07, index u8                                                  (iOS → watch)
TRIGGER_RESULT= 0x08, index u8, result u8                                       (watch → iOS)
APP_LIST_REQ  = 0x09                                                            (iOS → watch; empty body)
APP_LIST      = 0x0A, chunk_index u8, is_final u8, then entries until the chunk's byte cap   (watch → iOS)
```

### Remote triggers

`TRIGGER_LIST` replaces the watch's whole stored trigger list (sent after every HELLO and whenever the user edits the list). Trigger kinds: `0` = launch Android package (payload = package name, e.g. `com.spotify.music`), `1` = deep link (payload = URI). **The watch executes only triggers from its stored list — `TRIGGER_FIRE` carries an index, never a raw payload off the wire.** The watch persists the list (SharedPreferences) across reboots. `TRIGGER_LIST` total size must stay ≤ one B5 notification (≤ 509 B; iOS caps the list accordingly).

`TRIGGER_RESULT.result`: `0` launched, `1` no such trigger, `2` launch failed (no activity for package / no handler for URI), `3` blocked by system (background-activity-launch restriction — watch posted a one-tap fallback notification instead).

### Watch app list (picker source)

`APP_LIST_REQ` (empty body) makes the watch reply with its launchable apps as one or more `APP_LIST` writes on …B5. Entry = `[u16 pkg_len][utf8 package][u16 label_len][utf8 label][u8 standalone]` — `standalone` = 1 when the app's manifest declares meta-data `com.google.android.wearable.standalone` = true (works without any phone; 0 otherwise). Both codecs treat the byte as REQUIRED: an entry without it is malformed. Watch sends apps with a MAIN/LAUNCHER intent (excluding itself), label falling back to the package name, sorted by label case-insensitively. A full list usually exceeds one write, so it is **paged**: each write = `[u8 0x0A][u8 chunk_index][u8 is_final]` + as many entries as fit in ≤ 480 B total. chunk_index starts at 0 and increments; the final chunk has `is_final = 1`. The watch enqueues all chunks in order through its serialized write queue so chunks can't reorder.

iOS reassembles: chunk 0 starts a fresh assembly; a chunk whose index ≠ expected+1 resets the partial list (the UI offers a retry); `is_final` flushes the assembled list; a ~5 s timeout aborts a stalled fetch. An empty final chunk is valid (watch has no other launchable apps).

`install_nonce` = random u32 generated once per watch-app install and persisted (used by iOS to key its dedupe watermark — a watch reinstall resets record IDs to 1 and must not collide with the old watermark).

`type_bitmap` bits 0–7 = record types 1–8 the watch intends to send (informational).

`cursor_record_id` = the watch's oldest unsent record ID (informational for iOS).

## Link watchdog

A GATT link can die silently (no disconnect callback — observed on hardware).
While …B5 is subscribed, the watch sends PING every 60 s. Two consecutive
unanswered PINGs (~120–180 s of silence) ⇒ the watch treats the link as a
zombie and forces a GATT disconnect; the normal reconnect machinery then takes
over (iOS state restoration relaunches the app when the central reconnects).

## Sync semantics

1. Watch collects into Room; nothing deleted until acked.
2. On link (re)connect: watch sends HELLO; iOS replies TIME_SYNC.
3. Watch sends batches oldest-first; each write = one complete batch.
4. iOS processes records **in order**: writes to HealthKit, then ACKs the contiguous prefix of records that saved successfully. First failure stops the batch (watch resends from that record next round).
5. iOS dedupes by watermark: per install_nonce, the highest acked record ID. Records ≤ watermark are re-ACKed without writing. The watermark is persisted BEFORE the ACK is sent.
6. Workouts: header + stream chunks accumulate iOS-side; all of a workout's record IDs are ACKed together only when the workout is written to HealthKit (counts reached or a new header forces a partial flush). Un-acked chunks get resent by the watch; iOS dedupes chunks by record ID within the pending workout.
7. ACK capacity: `count u8` ≤ 63 record IDs per ACK (8 B × 63 + 2 < 509).

## Design-doc deltas (clarifications added during iOS implementation)

specs/design.md listed payloads without record IDs and left workout completion undefined. Resolved here: record_id prefix on every payload (item 2 STEPS became an interval, SLEEP_SUMMARY gained a window, WORKOUT_HEADER gained hr/gps record counts, HELLO gained install_nonce, PONG added). Update specs/design.md if these deltas are accepted.
