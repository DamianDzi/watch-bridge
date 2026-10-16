# watch-bridge

Personal tool: connect a **OnePlus Watch 3** (Wear OS 5.1) to an **iPhone 16 Pro Max** with no subscription.

Built on the MIT-licensed [WearBridge](https://github.com/k97/WearBridge) (vendored at `third_party/WearBridge`, pinned commit `c9ff912`). The official companion route is impossible — no Wear OS 3+ watch supports iOS — so this uses the bridge pattern: a **watch-side Kotlin app acts as the BLE central**, bonds with the iPhone, and consumes Apple's standard accessory services (ANCS = notifications, AMS = media). A small **iOS companion app** receives health data over custom BLE characteristics and writes it to Apple Health.

## Status

SDLC harness phases: `0 REQ 1 PLAN 2 BUILD 3 TEST 4 REV 5 SEC 6 SHIP 7 SMOKE 8 DEBUG` — currently **0/1** (discovery + design; see `specs/`).

## Feature targets

| Feature | Source | Mechanism |
|---|---|---|
| Notifications (incl. dismiss sync) | from WearBridge | ANCS |
| Call alert + decline | from WearBridge | ANCS + Telecom overlay |
| Media control | from WearBridge | AMS |
| **Health → Apple Health** | **to build** | Wear OS Health Services → custom BLE → HealthKit |
| OnePlus Watch 3 battery tuning | to build | battery-optimization exemption + Doze tuning |

Hard-blocked by Apple (out of scope, forever): answering calls on the watch (HFP needs MFi), replying to SMS/iMessage from third-party apps.

## One-time setup (needs an Android phone once)

The watch must be activated via OHealth on Android (~15 min). After that Android is only needed for firmware updates. See `docs/setup.md` once written.

## Repo layout

- `specs/` — requirements, design, implementation plan (SDLC harness artifacts)
- `third_party/WearBridge/` — upstream reference, pinned; keep its LICENSE on any derivative code
- `wearos/`, `ios/` — our apps (created in phase 2)

## Build requirements (not yet installed on this Mac)

- Watch app: JDK 17+, Android SDK (via Android Studio), device = OnePlus Watch 3
- iOS app: full Xcode (only Command Line Tools present), signing for personal device via free Apple ID
