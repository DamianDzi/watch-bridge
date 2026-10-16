# watch-bridge — Design (Phase 1)

*2026-10-16. Base: WearBridge @ c9ff912 (MIT). Analysis of upstream in git history of this doc; read `third_party/WearBridge/.claude/rules/` (ble-ancs-protocol.md, gotchas.md, future-work.md) before coding — 16 documented footguns.*

## What we inherit vs. build

| Capability | Source | Work |
|---|---|---|
| Notifications (ANCS), dismiss sync | WearBridge | none |
| Call alert/decline + call-overlay | WearBridge | none |
| Quick reply (calls) via `shortcuts://` | WearBridge | none (may need our own iOS Shortcut) |
| **Health → Apple Health** | — | **greenfield #1 (this project's core)** |
| **Media control (AMS)** | — | **greenfield #2 (phase 2b, after health)** |
| OPW3 battery/reconnect hardening | — | config + testing |

Upstream facts that shape the design: watch app = Kotlin/Compose, minSdk 33 / compileSdk 35 (OPW3 on Wear OS 5.1 ✓), foreground service (`connectedDevice` type), Room already a dependency, auto-reconnect with backoff. iOS app = SwiftUI, `UIBackgroundModes: bluetooth-peripheral` + state restoration, xcodegen project. Custom companion GATT service `A0E3D5B1-4C6F-4E8A-9B2D-7F1E3A5C8D0B` (iOS = GATT server via CBPeripheralManager, watch = client) with characteristics `…B2` (conn status), `…B3` (quick reply), `…B4` (call state). No HealthKit entitlement upstream; author's DEVELOPMENT_TEAM must be replaced.

## Structure decision

Copy `third_party/WearBridge/{wearos-app,ios-app}` → `apps/wearos`, `apps/ios` as our derivative (one-time copy, attribution in each app's README + LICENSE preserved). The vendored copy stays untouched as reference. Avoids `third_party/` semantics for actively-edited code.

## Health bridge (greenfield #1)

### Watch side (`apps/wearos`, Kotlin)

- **Collection** via Wear OS Health Services (`androidx.health:health-services-client`):
  - `PassiveMonitoringClient` → HR, steps, sleep (incl. stages as available) as `PassiveListener` events; daily aggregates.
  - `ExerciseClient` → active workout sessions (type, duration, HR stream, GPS via `ExerciseTypeLocation` if user enables it).
- **Buffering**: Room tables `health_records(record_id PK, type, start_epoch_ms, end_epoch_ms, value, meta_json, sent_at NULL)`. Records are inserted on collection; nothing is deleted until acked. Backfill falls out of the schema.
- **Transport** — extend companion service with two characteristics (same service UUID, new tail):
  - `A0E3D5B1-…-B5` **health-control** (write from watch / notify from iOS): commands `HELLO {types, cursor}` / `ACK {record_id}` / `TIME_SYNC {epoch}` / `PING`.
  - `A0E3D5B1-…-B6` **health-data** (write from watch): batched records.
- **Record framing** (upstream style = raw bytes, no protobuf): TLV records, batch capped at negotiated MTU − 3 (typically 509 B):
  `batch = [u8 count] record+`; `record = [u8 type][u16 len][payload]`
  Types: `1 HR bpm u16 + start_ms u64`, `2 STEPS count u32 + day_start_ms u64`, `3 SLEEP stage u8 + start_ms u64 + end_ms u64`, `4 SLEEP_SUMMARY per-stage durations`, `5 WORKOUT_HEADER type u8 + start/end + summary stats`, `6 WORKOUT_HR_STREAM` (chunked as own records), `7 WORKOUT_GPS` lat/lng i32 ×1e7 + alt f32 + t u64`, `8 BATTERY pct u8 + ts`.
  On link (re)connect: watch sends HELLO with cursor = oldest unsent `record_id`; iOS ACKs in order; watch deletes acked rows.
- **Battery**: keep AncsService's foreground-service pattern for the health collector (or a second `dataSync` foreground service during active batch flush); add in-app guidance + `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` for OPW3 (upstream never needed it; Snapdragon W5 + OnePlus tuning may kill background BLE otherwise — this is Merge's <12h-battery failure mode).

### iOS side (`apps/ios`, SwiftUI)

- Add HealthKit capability + entitlement; `HKHealthStore` authorization request for: heartRate, stepCount, sleepAnalysis (+ `sleepStages` if iOS 26 API), workouts, route (GPS), `HKQuantityTypeIdentifier.heartRate` during workouts.
- New `HealthSync` module next to upstream `PeripheralManager`: on `…B5` HELLO → read `…B6` batches, decode TLV, map to HealthKit objects with dedupe:
  - Every written object gets `metadata["watch-bridge-id"] = record_id` (HKMetadataKeyExternalUUID). Before write, `HKAnchoredObjectQuery`-based local seen-set (persisted cursor of acked record_ids) skips duplicates — never rely on re-query alone.
  - Steps/sleep aggregates written as interval samples; workouts as `HKWorkout` + `HKWorkoutRoute` + HR samples with the workout as associated activity.
  - ACK per record after successful save; persist acked-ids (UserDefaults/file) so a reinstall doesn't re-import.
- Team ID: replace author's `R68RHN3HF5`; personal (free) signing acceptable — 7-day re-sign cycle noted in setup docs.

### Flow (happy path + outage)

1. Watch collects → Room. 2. Link up (existing reconnect) → HELLO(cursor). 3. Batches + ACKs until cursor current. 4. Ongoing: flush-on-insert with a short debounce; nothing real-time-critical. Outage (AC2/AC3): collection continues, flush happens on reconnect.

## Media control (greenfield #2, phase 2b)

iOS auto-exposes AMS (`89D3502B-0F55-4C87-94A9-4FEE378E005F`) to bonded accessories. Watch side only: AMS client — Remote Command (`…E2` write: play/pause/next/prev/volume), Track (`…B3` notify: artist/title/duration), Entity Update (`…B4` subscribe: player/queue). No iOS-side code needed. Small Compose "Media" screen; wire to existing BleConnectionManager. Lower risk than health; do second.

## Hardening (OPW3)

- Battery-optimization exemption flow (above).
- Upstream gotchas honored: #12 double-subscribe on rediscovery, #15 single ANCS subscriber, #8 full-screen-intent appops on API 34+ (call overlay), #16 answered-call = MODIFIED not REMOVED.
- Reconnect after iPhone reboot: iOS state restoration + watch backoff already upstream; verify on hardware, tune grace period if OPW3 vibrates storms.

## Build & deploy prerequisites (not on this Mac yet)

- JDK 17+ (Gradle wrapper 8.13 + foojay auto-provisions toolchain), Android SDK 35 — install Android Studio.
- Watch app via `adb install` (or Android Studio run) over the watch's Wi-Fi debugging / USB.
- iOS: full Xcode (iOS 26 SDK), `brew install xcodegen`, our Apple ID signing.
- One-time watch activation via OHealth on the Android phone (borrowed) BEFORE any of this.

## Risks

- R1: OnePlus's battery management kills the health collector → mitigation above; test early (day 1 smoke test = AC7).
- R2: Health Services on OPW3 restricts passive HR sampling density (OEM-dependent) → fall back to MeasureClient polling during workouts + PassiveMonitoring for the rest; degrade gracefully.
- R3: iOS 26 free-signing 7-day expiry breaks background health sync silently → app shows "re-sign needed" on launch; acceptable for personal use.
- R4: Apple restricting ANCS/background peripheral modes → nothing to do; upstream pattern has been stable ~10 years.
