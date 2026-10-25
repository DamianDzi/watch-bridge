# Contributing

**The best first contribution is a hardware report** — install it, try it, and either
add your row to the README's tested-hardware table (PR) or open an issue with what
happened. Every `⚠️ implemented, unverified` row in the README is waiting for exactly that.

## High-value next features (from `specs/roadmap.md`)

- **B1 — Media control (AMS):** watch-side only (iOS exposes Apple Media Service to bonded
  accessories automatically; zero iPhone code). Well-specced in `specs/design.md`.
- **B2 — more health types:** SpO2, stress, skin temperature, respiratory rate — record
  types 9–12; additive to the protocol (the iPhone side already ignores unknown types).
- **The startup-wedge bug:** a service started by the update receiver occasionally comes
  up running-but-silent (never initiates the Bluetooth connection; force-stop + relaunch
  recovers). Repro notes in the project's docs — a clean fix here is worth a lot.

## Building

- Watch app (`apps/wearos`): Android Studio or just
  `JAVA_HOME=<jdk17+> ANDROID_HOME=<sdk> ./gradlew test assembleDebug`
- iOS app (`apps/ios`): `brew install xcodegen`, then `xcodegen generate`, then build in
  Xcode with **your own** Personal Team (see `docs/setup.md`). The repo ships with an empty
  `DEVELOPMENT_TEAM` on purpose — set yours in `apps/ios/project.yml` (never commit it back).

## Ground rules

- The wire protocol is a contract: `specs/health-ble-protocol.md`. Both codecs implement
  it with golden-vector tests on each side — if you change one side, change both and the doc.
- New record types must be **additive** (old apps skip unknown types by design).
- Keep the honest feature matrix in `README.md` updated — that table is the project's
  main credibility. A PR that marks something ✅ should say how it was verified.
- Conventional commits; tests green (`./gradlew test` watch-side; `xcodebuild test` iOS).
