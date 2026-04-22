# FireTV Dashboard — Product Requirements Document

> **Status:** Build pipeline GREEN — build 105 succeeded and deployed. Runtime fixes applied. Ready for hardware verification.
> **Last updated:** 2026-04-22
> **Owner:** Brian / Hermes Agent

---

## 1. Overview

**What:** Android/Fire TV application that displays the family schedule dashboard (`https://dashboard.cashlabnyc.com`) in a full-screen WebView on a TV, running 24/7 as a wall display / kiosk.

**Why:** Replace a browser tab on a laptop or Chromecast with a purpose-built, auto-starting, self-healing kiosk app that survives power loss, network outages, and Fire Stick sleep — suitable for non-technical household/office use.

**Target hardware:**
- Amazon Fire TV Stick 4K (first generation, 2018 — Fire OS 7, API 28)
- Fire TV Stick 4K Max (2021 — Fire OS 8, API 31)
- Fire TV Cube (2022 — Fire OS 7, API 28)
- NOT supported: first-gen Fire Stick (no 4K, different GPU)

---

## 2. Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│  Fire TV Stick (Fire OS 7/8)                                        │
│                                                                     │
│  ┌─────────────┐   ┌──────────────────────┐   ┌─────────────────┐ │
│  │BootReceiver │──▶│  KeepAwakeService     │   │  DashboardActivity│ │
│  │ (BOOT_COMPLE│   │  PARTIAL_WAKE_LOCK    │   │  WebView         │ │
│  │  TED)       │   │  (foreground, sticky)│   │  - injects auth  │ │
│  └─────────────┘   └──────────────────────┘   │  - JS watchdog   │ │
│                                                │  - retry loop    │ │
│  ┌──────────────────────────────────────────┐  │  - offline overlay│ │
│  │  Android WebView                         │  └─────────────────┘ │
│  │  loads https://dashboard.cashlabnyc.com  │                       │
│  └──────────────────────────────────────────┘                       │
└──────────────────────────┬──────────────────────────────────────────┘
                           │ HTTPS (port 443)
                           ▼
┌─────────────────────────────────────────────────────────────────────┐
│  VPS: 2.24.198.162 (Traefik → Next.js on :8081)                   │
│  Dashboard URL: https://dashboard.cashlabnyc.com                    │
│  APK served at: https://dashboard.cashlabnyc.com/apk/firetv-release.apk│
└─────────────────────────────────────────────────────────────────────┘
```

### Key Design Decisions

| Decision | Rationale |
|----------|----------|
| WebView (not native UI or Chrome Custom Tab) | Single codebase, works for any web dashboard, fastest to build |
| Foreground Service for keep-awake | START_STICKY ensures restart after system kills |
| Exponential backoff retry | Prevents hammering server during extended outage |
| JS watchdog (90s stale threshold) | Detects JS hangs that don't trigger network errors |
| CI-injected auth token (BuildConfig) | No secrets in source; CI env var at build time |
| `singleTask` launchMode | Prevents multiple dashboard instances |
| Boot receiver for auto-start | Survives power loss, firmware update, reboot |
| APK hosted on VPS + ADB sideload | No Google Play; sideload is the Fire Stick channel |

---

## 3. Component Inventory

### 3.1 DashboardActivity
- Full-screen immersive WebView
- `FLAG_KEEP_SCREEN_ON` — keeps display on
- `FLAG_SECURE` — blocks screenshots (kiosk security)
- Auth header injection via `shouldInterceptRequest` for GETs to dashboard domain
- JS watchdog: injects `AndroidDashboard.pong()` every 30s; reloads if no pong in 90s
- Exponential backoff: 5s → 15s → 60s → 5min; max 10 retries before offline overlay
- Offline overlay: self-contained HTML (no external deps), countdown timer, manual Retry button
- Native reload button (bottom-right, ↻) — works even if JS is crashed
- Exit confirmation dialog — prevents accidental app exit by staff
- `onTrimMemory`: reloads only when `isInForeground=true` AND level ≥ MODERATE (not on BACKGROUND)

### 3.2 KeepAwakeService
- `START_STICKY` foreground service
- `PARTIAL_WAKE_LOCK` (NOT SCREEN_BRIGHT — that throws SecurityException on Fire OS 7+)
- Ongoing notification (non-dismissible, LOW priority, no badge)
- `ACQUIRE_CAUSES_WAKEUP` removed — causes unwanted screen flash on boot
- Note: does NOT suppress Fire TV screensaver — must be disabled in Settings

### 3.3 BootReceiver
- Listens for `BOOT_COMPLETED`
- Starts DashboardActivity with `FLAG_ACTIVITY_NEW_TASK`
- No-op on any other broadcast

### 3.4 CI/CD Pipeline (GitHub Actions)
1. Setup Android SDK (Google-hosted runner)
2. Build: `gradlew assembleRelease` → `app-release-unsigned.apk`
3. Verify: checks `app-release-unsigned.apk` exists
4. Sign: apksigner v2-only re-sign with `firetv-upload` key from secrets
5. Deploy: pipes APK over SSH → docker exec → nginx container at `/usr/share/nginx/html/apk/`

---

## 4. Security Model

| Concern | Mitigation |
|---------|-----------|
| Auth token in source | CI injects at build time via gradle.properties; empty in git |
| Screenshot/screen record | `FLAG_SECURE` on window |
| Cleartext traffic | `usesCleartextTraffic=false` + NetworkSecurityConfig; HTTPS only |
| WebView asset access | `allowFileAccess=false`, `allowContentAccess=false` |
| Mixed content | `MIXED_CONTENT_NEVER_ALLOW` |
| Exported components | BootReceiver exported=true required (system sends BOOT_COMPLETED); all others not exported |
| WebView remote debugging | Only in debug builds; disabled in release |
| Crash reporting | None currently (ACRA removed; Crashlytics optional future addition) |
| VPS dashboard auth | Traefik auth middleware missing — **manual step required on VPS** |

### Manual VPS Security Step (P2-04 — REQUIRES ROOT)
```
File: /etc/traefik/dynamic.yml (read-only ext4 mount inside container)
Fix: Add auth middleware to dashboard-https router
Status: BLOCKED — needs host root to remount filesystem rw
```

---

## 5. Fire Stick Setup Checklist

Before the app will work correctly as a 24/7 kiosk display, staff must perform these one-time setup steps on the Fire Stick:

### Required (screen stays on)
1. **Settings → Display & Sounds → Screen Saver → Never**
   - If left on any setting other than "Never," the TV will go black regardless of the app.
   - The app cannot override this programmatically without Device Owner (admin) provisioning.

2. **Settings → Display & Sounds → Auto Power Off → Never**
   - Prevents TV from powering off after a set idle period.

3. **Settings → My Fire TV → Developer Options → ADB Debugging → ON**
   - Required for sideloading the APK and for debugging.

4. **Settings → My Fire TV → Developer Options → Install from Unknown Apps → FireTV Dashboard → ON**
   - Allows the APK to be installed without the Amazon Appstore.

### Recommended (auto-start)
5. **Reboot the Fire Stick** after installing the app.
   - The BootReceiver will launch the dashboard automatically on next boot.

### Optional (remote access debugging)
6. **Settings → My Fire TV → Developer Options → ADB Debugging → ON** (already done)
   - For remote logcat: `adb connect <firetv-ip>:5555`
   - For WebView inspection: Chrome desktop → `chrome://inspect/#devices` (same network)

---

## 6. Deployment Instructions

### Build and Release (automatic)
Every push to `main` triggers GitHub Actions:
1. APK builds at: `https://dashboard.cashlabnyc.com/apk/firetv-release.apk`
2. Version JSON at: `https://dashboard.cashlabnyc.com/apk/version.json`

### Install on Fire Stick
```bash
# Option A: From the Fire Stick itself (browser approach)
# Open Silk Browser → navigate to:
#   https://dashboard.cashlabnyc.com/apk
# Tap firetv-release.apk → Install

# Option B: Via ADB from a computer on the same network
adb connect <firetv-ip>:5555
adb install -r https://dashboard.cashlabnyc.com/apk/firetv-release.apk

# Option C: Download APK to USB, use a file manager (Files by Mobile Essentials)
```

### Verify Installation
1. App appears as "FireTV Dashboard" in Settings → My Apps
2. App appears in the Fire TV home screen app row
3. On first launch, confirm full-screen dashboard loads within 10s

---

## 7. Operational Runbook

### Problem: Screen goes black but TV is on
1. Check if screensaver is disabled: Settings → Display & Sounds → Screen Saver → Never
2. Check TV "Auto Power Off" setting
3. Check HDMI cable
4. Press any button on remote — does the TV respond?
5. If TV is unresponsive: TV has entered a power state — unplug/replug Fire Stick

### Problem: Dashboard shows "Dashboard unavailable" overlay
1. Check WiFi/Ethernet connection on Fire Stick
2. Check if `dashboard.cashlabnyc.com` is reachable from another device
3. Check VPS is running: `curl https://dashboard.cashlabnyc.com/api/refresh`
4. Tap "Retry Now" or the ↻ button on the display
5. After 5 minutes, overlay auto-retries

### Problem: App is not in the launcher after reboot
1. BootReceiver may not have fired — launch app manually from Settings → My Apps
2. If it still doesn't appear, the app may have been uninstalled — reinstall

### Problem: Remote button presses show exit confirmation
1. Normal behavior — this is intentional kiosk protection
2. To exit: use the exit confirmation dialog ("Exit" button)
3. To disable: change `showExitDialog()` call in DashboardActivity.kt

---

## 8. Known Limitations

| Issue | Workaround |
|-------|-----------|
| Fire TV screensaver cannot be disabled programmatically | Must be disabled manually in Settings |
| App cannot run as true kiosk (lock to single app) | Requires Device Owner provisioning via ADB — not implemented |
| No crash reporting currently | APK must be retrieved from device for logcat analysis |
| Traefik dashboard is publicly accessible | Requires manual VPS root step to add auth |
| Fire Stick 4K (1st gen) is the minimum supported hardware | Fire Stick 1st gen (no 4K) is not supported |

---

## 9. Changelog

### v1.0 build 103 (2026-04-22)
- **Build pipeline:** Fixed 10 consecutive CI failures (acra-telegram missing, missing `</service>`, Kotlin import errors, APK filename mismatch, VPS deploy permission)
- **Runtime:** `SCREEN_BRIGHT_WAKE_LOCK` → `PARTIAL_WAKE_LOCK` (fixes SecurityException on Fire OS 7+)
- **Runtime:** `onTrimMemory` reload threshold raised to MODERATE, guarded by `isInForeground`
- **Runtime:** Native reload button added (JS-independent, works when WebView is crashed)
- **Runtime:** Removed forced `LAYER_TYPE_HARDWARE` (system now decides; better on constrained hardware)
- **Cleanup:** ACRA dependencies fully removed (acra-telegram doesn't exist in Maven Central)
- **Cleanup:** Removed unused `acra.properties` asset

### v1.0 build 1–102
- Pre-existing builds had various CI failures; current state reflects build 103

---

## 10. Open Issues

| ID | Priority | Issue | Owner |
|----|----------|-------|-------|
| P2-04 | P2 | Traefik `dashboard-https` router missing auth middleware (VPS root required) | Brian (manual) |
| — | P3 | Fire TV screensaver requires manual disable in Settings | Staff (setup) |
| — | P3 | No true kiosk lockdown (single-app lock requires Device Owner provisioning) | Future |
| — | P3 | No crash reporting (file-based logger or Crashlytics recommended) | Future |

---

## 11. API Reference

### APK Distribution
```
GET https://dashboard.cashlabnyc.com/apk/firetv-release.apk
GET https://dashboard.cashlabnyc.com/apk/version.json
Response: {"version":"1.0","build":N,"apk_url":"https://..."}
```

### Dashboard API (internal)
```
GET https://dashboard.cashlabnyc.com/api/refresh
Header: X-App-Auth: <token>   (injected by CI build, not in source)
Response: {schedule: [...], events: [...], unscheduled_events: [...]}
```

### WebView JS Interface
```javascript
// Injected by DashboardActivity:
window.AndroidDashboard.pong()      // Resets JS watchdog timer
window.AndroidDashboard.logFromJs('INFO|WARN|ERROR', message)  // Logs to logcat

// Dashboard's own JS should call pong() every 30s to signal health
```

---

*This PRD is the source of truth. Update this document before making changes to the codebase.*
