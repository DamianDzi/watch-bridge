# watch-bridge — Roadmap (post-AC1, 2026-10-18)

Working on hardware: notifications (AC1 ✓), HR sync round-trip (watch→BLE→HealthKit→ACK),
stable app on OPW3 (post telecom-guard + wear-compose 1.5.6 fixes), free personal signing.
Pending: AC1b dismiss-sync, AC2 walk backfill, AC3 overnight sleep, AC5 call decline,
AC6 reboot reconnect, AC7 battery/48 h.

## Phase A — finish verification (harness phase 3; days, user-assisted)
- A1 Complete AC checklist (specs/spec.md) — user tests + logs
- A2 Execute iOS unit tests (xcodebuild test — Xcode now installed)
- A3 Verify sleep-stage mapping against first real night of data
- A4 Verify OHealth/OPW3 actually publishes sleep + workouts into Health Connect (read tomorrow's logs)
- A5 Sync chattiness: debounce flushes (currently ~1 record/s; batch to 30 s / 20 records — battery + BLE traffic win)

## Phase B — core feature completion
- B1 AMS media control (T8; watch-side only, no iOS code; the planned greenfield #2)
- B2 More health types: SpO2, stress, skin temperature, respiratory rate — protocol types 9–12, additive (iOS ignores unknown types by design)
- B2b Sleep inference from overnight HR stream — DEFERRED by user decision 2026-10-20 (battery prioritized); revisit if OHealth-on-OP12 stops being acceptable
- B7 Battery optimization — PRIORITIZED 2026-10-20: periodic MeasureClient HR (5 min) instead of continuous passive, heartbeat 60s→180s, Room prune valve
- B3 Auto-detected workouts (Health Connect exercise sessions, incl. auto ones)
- B4 Find-my-phone (custom characteristic → iOS alert sound)
- B5 Remote intents: iPhone → watch "fire intent / open deep link" control messages (launch apps, trigger quick-log shortcuts in e.g. Baby Daybook)
- B6 MediaSession read: watch-side now-playing for local watch players (e.g. Spotify standalone) surfaced to iPhone

## Phase C — polish & reliability
- C1 iPhone widget + Wear OS tile (watch battery %, sync status, pending records)
- C2 Per-app notification filtering + notification history
- C3 Contact sync (custom char, iOS Contacts read)
- C4 Re-sign reminder: day-6 local notification on iPhone (free-signing 7-day expiry)
- C5 dev helper: adb mdns auto-reconnect script (Wi-Fi debug drops on sleep)
- C6 OnePlus battery-manager deep-optimization allowlist guidance (AC7 insurance)

## Phase D — infrastructure (parallel, low effort, high value)
- D1 Private GitHub remote + push — repo is LOCAL-ONLY right now (single point of failure)
- D2 CI on GitHub Actions: gradle test + xcodebuild build
- D3 Robolectric DAO tests (skipped in T2)
- D4 Decide paid Apple Developer account ($99/yr: permanent install, no 7-day cycle, TestFlight option)

## Phase E — optional / productization (explicit decision required, not default)
- E1 Ship to App Store: market validated (Merge $36–60/yr, Bridge ~$20/yr, both rated mediocre);
      a one-time $9.99 undercut is plausible. Needs polish, support, compliance. Personal-tool-first
      decision stands until this is explicitly chosen.
- E2 Quick reply beyond calls: Apple blocks SMS/iMessage; the shortcuts:// workaround could extend
      to WhatsApp/Telegram/Signal (they have URL schemes) — partial by design.
- E3 Camera remote: effectively blocked on iOS without MFi — skip.
- E4 Multi-watch / multi-user support — only if productized.

## Recommended order
A (finish what we started) → D1 (backup — 30 min, protects everything) → B1 (media) → B2 (more
health types) → C4/A5 (quality of life) → then decide E deliberately.

Every phase is weekend-scale for a solo dev; nothing here blocks daily use — the bridge already
delivers its two headline features.

## STATUS: DELIVERED & PAUSED 2026-10-25 (until further notice)

Live on hardware: notifications (ANCS), health sync HR/steps/battery with offline backfill, remote triggers + app picker with standalone badges, self-healing link watchdog, auto-restart on update. Deferred/paused: B1 AMS media, B2 SpO2/stress/skin-temp, B2b sleep inference (user decision), startup-wedge debug (known issue), AC checklist items (dismiss-sync, call decline, reboot reconnect, 48h battery measurement — informal/user-driven).
