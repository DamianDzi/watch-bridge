# watch-bridge — Requirements Spec (Phase 0)

*2026-10-16. Sources: compatibility/feasibility research (2026-10-16), Damian's feature picks, WearBridge upstream docs.*

## Problem

OnePlus Watch 3 officially requires an Android 9+ phone with OHealth; no Wear OS 3+ watch pairs with iOS. Paid bridge apps (Merge $36–60/yr, Bridge ~$20/yr) prove the functionality is achievable but cost a subscription and have OnePlus-Watch-3-specific reliability issues (calls failing, <12h battery drain from background BLE).

## Goal

A free, personal bridge: OnePlus Watch 3 ↔ iPhone 16 Pro Max, built on MIT-licensed WearBridge, with no subscription and source we control.

## Non-goals

- Shipping on the App Store (possible later; out of scope now — sideload via Xcode)
- Answering calls on the watch — Apple-blocked (HFP requires MFi hardware)
- Replying to SMS/iMessage — Apple-blocked (no public API for third-party message replies)
- Watch firmware updates / OnePlus watch-face store — Android-only, out of scope
- Reverse-engineering the OHealth BLE protocol — unnecessary; health data reads via public Wear OS Health Services API on-watch

## Functional requirements

F1. **Notifications**: iPhone notifications appear on the watch (from WearBridge's ANCS client); dismissing on either device syncs to the other. Per-app filtering acceptable as upstream provides it.

F2. **Health → Apple Health** *(the feature we build)*: watch-collected data — heart rate (continuous + workouts), steps, sleep (incl. stages where Health Services provides them), workouts (type, duration, HR stream, GPS trace where recorded), battery — is delivered to the iPhone and written into Apple Health via HealthKit. Data must survive a link outage: batch/backfill when reconnected, not only live streaming.

F3. **Media control**: play/pause/next/volume from the watch (AMS, from WearBridge).

F4. **Calls**: incoming-call alert on watch + decline (from WearBridge). Answering is explicitly not attempted.

F5. **Stability/battery**: auto-reconnect after phone reboot/watch out of range; watch battery impact comparable to the Android companion experience (≥ 2 days with AOD off) — requires battery-optimization exemption guidance for the OPW3.

## Constraints

- C1. Devices: OnePlus Watch 3 (Wear OS 5.1, Snapdragon W5, LTE variant?), iPhone 16 Pro Max (iOS 26).
- C2. One-time activation via OHealth on a borrowed Android phone is available and acceptable.
- C3. Watch app installed via Android Studio/adb (no Play Store listing needed for personal use).
- C4. iOS app sideloaded with a free personal Apple ID (7-day re-sign cycle acceptable) unless a paid dev account exists.
- C5. Build toolchain is NOT yet installed on this Mac — code authoring in this phase; builds deferred (needs JDK 17+, Android SDK, full Xcode).
- C6. Derivative of WearBridge must retain its MIT license attribution.

## Acceptance criteria (phase 3 test plan seeds)

- AC1: With both apps running, an iMessage arriving on the iPhone shows on the watch within ~5 s; dismissing on the watch removes it from iPhone's notification center.
- AC2: A 30-min walk recorded on the watch (no phone) appears in Apple Health (workout + HR samples + steps) within 10 min of the devices reconnecting.
- AC3: Overnight sleep (recorded on watch, phone out of range) backfills into Apple Health the next morning.
- AC4: Watch controls iPhone Music playback (play/pause/next) and volume.
- AC5: Incoming iPhone call triggers watch alert; tapping decline rejects the call.
- AC6: Rebooting the iPhone restores the bridge within 60 s of unlock, no manual app opening.
- AC7: Watch battery drain from the bridge ≤ 8%/day in steady state (measured over 48 h, AOD off).
