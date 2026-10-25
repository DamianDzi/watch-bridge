---
name: Bug report
about: Something doesn't work
labels: bug
---

**Watch model + Wear OS version:**

**iPhone model + iOS version:**

**What happened:**

**What you expected:**

**Logs (if you can):**
- Watch: `adb logcat -d --pid=$(adb shell pidof com.wearos.ancsbridge)` — connect over Wi-Fi debugging first (keep the Wireless debugging screen open, the link dies when the watch dozes)
- iPhone app: Xcode console while running
- App database (health issues): `adb shell run-as com.wearos.ancsbridge cat databases/health_bridge.db` and `sqlite3` it — `SELECT type, COUNT(*) FROM health_records GROUP BY type;` (1=HR, 2=steps, 3=sleep, 4=sleep summary, 5-7=workout, 8=battery)

**Did you re-pair after installing an update?** (stale Bluetooth state after app reinstalls
causes "connected but nothing works" — see docs/setup.md §Troubleshooting)
