# watch-bridge — Implementation Plan (Phase 1 → 2 handoff)

*2026-10-16. Sequenced per specs/design.md. Each task = one branch/PR-equivalent commit on the worktree flow; TDD where a unit-testable seam exists (TLV codec, record mapping, ack logic).*

## Prereqs (user actions, blocking builds — can overlap with code authoring)

- P1. Install Android Studio (brings JDK 21 + SDK 35) and Xcode (iOS 26 SDK) + `brew install xcodegen`.
- P2. One-time: activate OnePlus Watch 3 via OHealth on an Android phone; enable Wi-Fi debugging on the watch.
- P3. Decide signing: free personal Apple ID (7-day re-sign) vs paid developer account ($99/yr).

## 2a. Health bridge (core)

- T1. `apps/` derivative: copy wearos-app + ios-app from vendored c9ff912, replace DEVELOPMENT_TEAM, attribution headers, builds green (needs P1).
- T2. Watch: Room `health_records` schema + DAO + unit tests.
- T3. Watch: TLV codec (encode/decode, MTU chunking) — pure Kotlin, full unit tests first.
- T4. Watch: Health Services collectors (PassiveMonitoring + ExerciseClient) → Room inserts; battery-exemption guidance UI.
- T5. Watch: health transport on companion service (`…B5` control, `…B6` data) integrated with existing BleConnectionManager + reconnect/backfill (HELLO cursor → batches → ACK → delete).
- T6. iOS: HealthKit entitlement + authorization; TLV decode (mirror unit tests); seen-set dedupe; HK sample/workout/route writers; ACK + persistence.
- T7. Hardware integration test: AC2 (30-min walk backfill), AC3 (overnight sleep backfill), AC7 (battery over 48 h). Tune Doze/exemptions from results.

## 2b. Media control (AMS)

- T8. Watch: AMS client (Remote Command + Track + Entity Update) + Compose media screen, wired into BleConnectionManager. iOS: zero code (system AMS).
- T9. Hardware test AC4.

## 2c. Regression & hardening

- T10. Verify AC1/AC5/AC6 (inherited features still work on OPW3 + iOS 26); gotchas #8/#12/#15/#16 checks.
- T11. `/code-review` + `/security-review` pass on our diff vs upstream; docs: `docs/setup.md` (activation, installs, signing, daily use).

## Definition of done (phase 3)

All AC1–AC7 in specs/spec.md pass on real hardware, measured over ≥ 48 h.
