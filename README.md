# watch-bridge

**Free, open-source bridge between your Wear OS watch and your iPhone.**

Wear OS watches can't pair with iPhones — Google dropped iOS support at Wear OS 3, and the paid
bridge apps charge $20–60/yr for the workaround. watch-bridge is that workaround, open source:
a small app on your watch (Kotlin) + a small app on your iPhone (SwiftUI) talking over
Bluetooth LE through Apple's own accessory services. No subscription, no account, no cloud —
the data path is your wrist to your phone and nothing else.

Based on the excellent MIT-licensed
[WearBridge](https://github.com/k97/WearBridge) by karthik, extended with a
health-sync bridge, remote triggers, a self-healing link, and battery
optimization. Same MIT license — see `LICENSE`.

## What works (and what doesn't)

Honest matrix — this is a personal project, maintained in the open:

| Feature | Status | Notes |
|---|---|---|
| iPhone notifications on the watch | ✅ verified on hardware | via Apple ANCS; per-app icons, sender name as title, message as body |
| Notification dismiss sync | ✅ implemented | swipe away on either device; verified by code + upstream design, not lab-tested |
| Heart rate → Apple Health | ✅ verified on hardware | 5-minute sampling (battery-first); ~288 samples/day |
| Steps → Apple Health | ✅ verified on hardware | 5-minute buckets; stationary periods produce zero records |
| Battery level on iPhone app | ✅ verified on hardware | shown in the iOS app's Health Sync card |
| Offline buffering + backfill | ✅ verified on hardware | 22,000+ records backfilled after a multi-day outage |
| Workouts → Apple Health | ⚠️ implemented, unverified | reads Health Connect exercise sessions (HR stream, GPS route, distance, calories); **no Wear OS app found that publishes workouts to watch-local Health Connect — may not trigger on any device** |
| Sleep → Apple Health | ❌ not available | no Wear OS sleep tracker writes to the watch's local Health Connect (verified on OnePlus Watch 3); sleep inference from HR is specced in `specs/roadmap.md`, not built |
| Incoming-call alert + decline | ⚠️ implemented, unverified | decline rides on ANCS actions; the system call overlay is guarded — OnePlus Watch 3 has no Telecom feature, watches with it (Pixel/Galaxy) get the full overlay |
| Remote triggers (launch watch apps from the iPhone) | ✅ verified on hardware | app picker streams the watch's installed apps over BLE; "works without phone" badges from each app's own standalone declaration |
| Find-my-phone, quick reply (calls) | ✅ from upstream WearBridge | quick reply uses iOS Shortcuts (Apple blocks third-party SMS replies) |
| Answering calls on the watch | ❌ impossible | Apple restricts HFP to MFi hardware; no app can do this |
| Media control (play/pause/volume) | 🚧 not built | next roadmap item (B1) — iOS exposes AMS to bonded accessories, watch-side only |
| SpO2 / stress / skin temperature | 🚧 not built | roadmap B2; the wire protocol takes new record types without touching the iPhone side |

## Tested hardware

| Watch | Phone | Status | Reported by |
|---|---|---|---|
| OnePlus Watch 3 (Wear OS 5.1) | iPhone 16 Pro Max (iOS 26) | everything in the ✅ rows above | maintainer |

**Not tested:** every other watch. The architecture is standard Wear OS + ANCS and should
work broadly (it's *more* robust on watches that have the Telecom feature), but you are the
test lab — open a PR adding your row, or an issue with what broke.

## Build & install

Both apps build with free tooling and a free Apple ID (7-day re-sign cycle — see the guide):

**→ `docs/setup.md`** — step-by-step: one-time watch activation (any Android phone, ~15 min,
borrowed is fine), Android Studio + `gradlew assembleDebug` + `adb install`, Xcode +
`xcodegen` + personal-team signing, first pairing, and the troubleshooting section
(it contains the fixes for every failure mode hit on real hardware, including the
forget-and-repair ritual for stale Bluetooth state after app reinstalls).

## How it works

```
┌─────────────────┐         BLE (bonded)          ┌──────────────────┐
│  Wear OS watch   │ ◄───────────────────────────► │      iPhone      │
│  watch-bridge    │   ANCS (notifications)        │  watch-bridge    │
│  app (Kotlin)    │   custom companion service    │  app (SwiftUI)   │
│                 │   health records + acks        │                  │
└─────────────────┘   triggers, heartbeat         └──────────────────┘
```

- The **watch app is the Bluetooth central**: it bonds with the iPhone and consumes
  Apple's standard accessory services (ANCS for notifications, AMS is next).
- **Health sync**: the watch reads its own sensors and Health Connect, buffers records
  in a local database (nothing is lost on link outages — see the backfill row above),
  and streams them over a custom GATT service; the iPhone writes them into Apple Health
  with per-record IDs for dedupe. Byte-level contract: `specs/health-ble-protocol.md`.
- **Self-healing link**: a 60→180 s PING/PONG heartbeat; two unanswered pings trigger a
  hard teardown and reconnect (silent radio deaths were the #1 real-world failure of
  this and every paid bridge app).
- **53+ unit tests**, protocol golden vectors on both sides, and a wire format with a
  written contract both apps implement.

## Status & contributing

This is a **gift, not a product** — the maintainer built it for his own wrist, verified
what the matrix above says, and is publishing it so others don't have to pay Merge $60/yr.
Development is currently **paused/maintenance-mode**: issues are read, good PRs are
merged, but nobody is on-call. If you want a feature (media control is the big one —
it's watch-side only and well-specced), `specs/roadmap.md` is the menu and
**CONTRIBUTING.md** is the door. The best first contributions are **hardware reports**
(does it work on your watch?) — they cost you one evening and help everyone.

## Credits

- **[WearBridge](https://github.com/k97/WearBridge)** by karthik (MIT) — the foundation:
  ANCS client, call handling, reconnect machinery, and a documented set of gotchas
  (`third_party/WearBridge/.claude/rules/`) that saved this project weeks.
- The paid apps (Merge, Bridge) for proving the demand — and for showing exactly
  where a personal tool should differ.

## License

MIT — `LICENSE`. Derivative of WearBridge (MIT). Keep attribution on forks.
