# watch-bridge — Setup Guide

*DRAFT (2026-10-16): written before first hardware build; steps marked ⚠ are unverified until first install on the OnePlus Watch 3 + iPhone 16 Pro Max.*

## 0. One-time: activate the watch (borrowed Android phone, ~15 min)

1. Install **OHealth** (Play Store) on any Android 9+ phone.
2. Pair the OnePlus Watch 3 via OHealth and sign in with your Google account on the watch.
3. On the watch: **Settings → About → tap build number 7×** to enable Developer options, then **Settings → Developer options → Wireless (Wi-Fi) debugging → On**. ⚠
4. The Android phone is no longer needed — except for future watch firmware updates (OnePlus ships those via OHealth).

## 1. Install toolchains on the Mac

- **Android Studio** (brings JDK + Android SDK 35): https://developer.android.com/studio
- **Xcode** (full, from the Mac App Store — Command Line Tools alone are NOT enough)
- `brew install xcodegen`

## 2. Build & install the watch app ⚠

```bash
cd apps/wearos
# pair adb with the watch (Wi-Fi debugging screen shows IP:port + pairing code):
adb pair <ip:pairing-port>
adb connect <ip:port>
./gradlew :app:installDebug
```

On the watch, open the app once: grant notifications access, disable battery optimization for it when prompted (**critical on OnePlus** — otherwise Wear OS kills background BLE, the same failure mode behind Merge's <12h battery reports).

## 3. Build & install the iOS app (free personal signing) ⚠

```bash
cd apps/ios
xcodegen generate
open AncsBridge.xcodeproj
```

In Xcode: add your Apple ID (Settings → Accounts), select your personal team for the AncsBridge target, then run on the iPhone. On the phone: Settings → General → VPN & Device Management → trust the profile; allow HealthKit access (write) when the app first asks; keep Background App Refresh ON for the app.

**Free-signing limit:** the app stops launching after 7 days — just re-run it from Xcode (30 seconds). The app shows a warning on launch when it's nearing the limit.

## 4. Pair watch ↔ iPhone

1. On the iPhone, launch the app once — it starts advertising the companion service.
2. On the watch app, start the connection (it scans + bonds; accept the pairing dialog on the iPhone and on the watch).
3. Send yourself a test notification — it should appear on the watch within seconds (AC1).

## 5. Daily use

- Bluetooth on, both apps installed; nothing else to do. Health data syncs in the background and backfills after outages.
- If notifications stop after an iPhone reboot: unlock the phone once — state restoration reconnects within ~60 s (AC6).

## Verification checklist (AC1–AC7)

See `specs/spec.md` §Acceptance criteria — run each after first successful pairing, before calling phase 3 done.

## Troubleshooting: after reinstalling either app via CLI

Replacing an app (adb install -r / devicectl) can leave stale bond-keyed GATT
state: the watch's subscribes fail with "write not permitted" (status 3) or
"invalid handle" (status 1) on the companion service while ANCS still works.
A Bluetooth toggle does NOT clear it — the state is keyed to the pairing.
Remedy (takes a minute): forget the pairing on BOTH devices (iPhone:
Settings → Bluetooth → (ⓘ) → Forget; watch: Settings → Connections →
Bluetooth → unpair), then reopen both apps and accept the pairing prompt.
The watch's buffered health records backfill to Apple Health on reconnect.

Also: the watch app auto-restarts after `adb install -r` via
MY_PACKAGE_REPLACED (BootReceiver) — Android does not restart sticky
services on package replace by itself.
