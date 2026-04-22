# FireTV Family Dashboard — Production Readiness PRD

## Context

A single Android/Fire TV application (`com.hermes.firetv`) that loads a Next.js dashboard via an embedded WebView and displays it as a wall-mounted kiosk display. The app is sideloaded onto Amazon Fire TV Stick hardware. It must run unattended 24/7, survive network outages, recover from crashes, and never blank the screen.

**Current state:** The app builds and runs on modern Fire Stick 4K hardware, but has 5 critical (P0) defects and 9 high-priority (P1) defects that make it unsuitable for unattended production deployment. A security audit (April 22, 2026) identified hardcoded credentials, a broken release-signing pipeline, an ineffective screen-wake mechanism, zero error recovery, and zero observability.

---

## Glossary

| Term | Meaning |
|------|---------|
| Fire OS | Amazon's Android-based OS for Fire TV hardware |
| WebView | Android's embedded Chromium-based web renderer |
| shouldInterceptRequest | WebView API that lets the app proxy/modify HTTP requests |
| WAKE_LOCK | Android power management flag preventing CPU or screen sleep |
| Kiosk mode | Locked-down device state where only one app is usable |
| DPC / Device Owner | Android Device Policy Controller — enforces kiosk lockdown |
| ACRA | Android Crash Reporting library — application-level crash collection |
| TTV WebView | Amazon's Fire OS–specific TV WebView (`com.amazon.webview.chromium`) |

---

## Project Overview

**Package:** `com.hermes.firetv`
**minSdk:** 22 | **targetSdk:** 34 | **compileSdk:** 34
**Language:** Kotlin 1.9.22 | **Gradle:** 8.4 | **AGP:** 8.2.2

**Architecture:** Single-Activity WebView app
- `DashboardActivity` — one Activity hosting a full-screen WebView
- `KeepAwakeService` — foreground Service attempting to prevent screen sleep
- `shouldInterceptRequest` — proxies all dashboard traffic, injects `X-App-Auth` header

**Target hardware:** Amazon Fire TV Stick 4K (1st and 2nd gen), Fire TV Stick 4K Max
**Fire OS compatibility:** Fire OS 6 (API 25–30) through Fire OS 7 (API 31–34)

---

## Problem Statement

The app currently fails the following production requirements:

1. **Install reliability** — Release APK may be dual-signed (v1+v2), causing `INSTALL_PARSE_FAILED_NO_CERTIFICATES` on older Fire TV hardware.
2. **Screen wake** — `KeepAwakeService` uses `PARTIAL_WAKE_LOCK` (prevents CPU sleep, not screen sleep) and constructs `MotionEvent` objects that are never dispatched. The screen blanks within 10–30 minutes of idle.
3. **Security** — Auth token hardcoded in source and logged in plaintext. Custom `TrustManager` bypasses all TLS certificate validation, enabling man-in-the-middle attacks.
4. **Fault tolerance** — No retry logic, no error overlay, no watchdog. Any network blip or JS error shows a permanently blank screen.
5. **Observability** — Zero crash reporting or structured logging. Unattended deployments give zero feedback when failures occur.
6. **Kiosk hardening** — No app lock, no `FLAG_SECURE`, back button can exit, other apps are accessible.
7. **Recovery** — No automatic recovery from WebView freeze, OOM, or process death.

---

## Success Criteria

- [ ] Release APK installs cleanly on Fire OS 6 and Fire OS 7 hardware
- [ ] Release APK is signed v2-only (v1 stripped) — verifiable with `apksigner`
- [ ] Auth token is not present in source code or decompiled bytecode
- [ ] All TLS connections use system trust store (no custom TrustManager)
- [ ] Screen stays on indefinitely (user must still disable Fire OS screensaver in settings)
- [ ] Dashboard auto-reloads within 30s of any network failure
- [ ] Dashboard auto-reloads within 60s of a JS/WebView hang
- [ ] App survives 8-hour unattended runs without blanking or freezing
- [ ] Crash reports are delivered via Telegram within 5 minutes of app crash
- [ ] No screenshots or screen recordings can be captured of the dashboard (FLAG_SECURE)
- [ ] Back button does not exit the app
- [ ] Release APK has no hardcoded secrets, no debug logging, `android:debuggable=false`

---

## High-Level Architecture

```
┌─────────────────────────────────────────────────────────┐
│  Fire TV Stick 4K (Fire OS 6/7)                         │
│                                                          │
│  ┌──────────────────────────────────────────────────┐   │
│  │  DashboardActivity (single Activity)              │   │
│  │                                                    │   │
│  │  ┌────────────────────────────────────────────┐  │   │
│  │  │  WebView (Chromium, hardware-accelerated)   │  │   │
│  │  │  loads: https://dashboard.cashlabnyc.com   │  │   │
│  │  │  X-App-Auth header injected via            │  │   │
│  │  │  shouldInterceptRequest                    │  │   │
│  │  └────────────────────────────────────────────┘  │   │
│  │                                                    │   │
│  │  JS interface: AndroidDashboard { ping(), log() } │   │
│  │  Watchdog: 60s timer → reload on JS silence       │   │
│  │  Error overlay: injected HTML on main frame error  │   │
│  └──────────────────────────────────────────────────┘   │
│                                                          │
│  ┌──────────────────────────────────────────────────┐   │
│  │  KeepAwakeService (foreground)                   │   │
│  │  SCREEN_BRIGHT_WAKE_LOCK +                       │   │
│  │  Window.FLAG_KEEP_SCREEN_ON                      │   │
│  │  NO fake touch, NO daemon thread                 │   │
│  └──────────────────────────────────────────────────┘   │
│                                                          │
│  ┌──────────────────────────────────────────────────┐   │
│  │  ACRA (crash reporting)                          │   │
│  │  Telegram bot notification on crash               │   │
│  └──────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
              │ HTTPS (Let's Encrypt, system trust store)
              ▼
  ┌───────────────────────────────────┐
  │  dashboard.cashlabnyc.com          │
  │  Next.js app (auth-protected)      │
  └───────────────────────────────────┘
```

---

## Security Design

### Credential Management
- Auth token injected at CI build time via `Gradle.properties` → `BuildConfig`
- `AUTH_TOKEN` constant removed from source entirely
- Token logged only as presence boolean (not value), guarded by `BuildConfig.DEBUG`
- Token stored in GitHub Actions Secrets (`secrets.DASHBOARD_AUTH_TOKEN`)

### TLS / Certificate Validation
- Custom `TrustManager` and `HostnameVerifier` **removed entirely**
- System default trust store used (includes Let's Encrypt since Android 4.2)
- `android:networkSecurityConfig` set with `cleartextTrafficPermitted="false"`
- Dashboard domain explicitly trusted via domain-config in `network_security_config.xml`

### App Hardening
- `android:debuggable` — `false` in release (controlled by `build.gradle.kts`)
- `android:allowBackup` — `false` (prevents `adb backup` extraction)
- `FLAG_SECURE` — set on Activity window (blocks screenshots/recording)
- `WebView.setWebContentsDebuggingEnabled` — only in debug builds
- WebView `allowFileAccess` — `false` (already set)

### Network Security Config
```xml
<!-- res/xml/network_security_config.xml -->
<!-- Block cleartext; trust system CAs; no user-added CAs -->
```

---

## Error Recovery Design

### WebView Error Recovery State Machine

```
[LOADING] ──→ [LOADED] ──→ [STALE_DETECTED] ──→ [RELOADING]
                   ↑               │
                   │               ↓
                   └── [ERROR] ←──┘
```

- **LOADED:** Dashboard loaded successfully. JS ping expected every 30s.
- **STALE_DETECTED:** No JS ping for 90s (3 × 30s intervals). Reload triggered.
- **ERROR:** `onReceivedError` for main frame fires. Exponential backoff: 5s → 15s → 60s → 5min → max 5min.
- **RELOADING:** `webView.loadUrl(url)` called. Returns to LOADING.
- **MAX_RETRIES:** After 10 consecutive failures, stop retrying and show persistent error overlay.
- **NETWORK_RECOVERY:** On `connectivityChange` broadcast (if registered), immediately retry once.

### Error Overlay
On main frame error, inject minimal HTML overlay:
```html
<!-- No external dependencies — fully self-contained -->
<div style="background:#121212;color:#fff;font-family:sans-serif;
            display:flex;align-items:center;justify-content:center;height:100vh">
  <div style="text-align:center">
    <div style="font-size:48px">⚠</div>
    <div style="font-size:24px;margin:16px 0">Dashboard unavailable</div>
    <div id="countdown" style="font-size:16px;opacity:0.6"></div>
    <div id="retry-btn" style="margin-top:24px;padding:12px 32px;
         background:#333;cursor:pointer;border-radius:8px">
      Retry Now
    </div>
  </div>
</div>
<script>
  // Countdown + retry button logic (self-contained, no external deps)
</script>
```

### JS ↔ App Communication (JavascriptInterface)
```kotlin
webView.addJavascriptInterface(object {
    @JavascriptInterface
    fun pong() { lastJsPong = System.currentTimeMillis() }

    @JavascriptInterface
    fun log(level: String, msg: String) {
        when(level) {
            "ERROR" -> Log.e(TAG, "[JS] $msg")
            "WARN"  -> Log.w(TAG, "[JS] $msg")
            else    -> Log.d(TAG, "[JS] $msg")
        }
    }
}, "AndroidDashboard")
```

JS side: `AndroidDashboard.pong()` called every 30s via `setInterval`.

---

## Logging / Observability Design

### Structured Log Tags
| Tag | Purpose |
|-----|---------|
| `HermesDashboard` | App lifecycle, Activity events |
| `HermesWebView` | WebViewClient callbacks, shouldInterceptRequest |
| `HermesNetwork` | HTTP response codes, SSL/TLS events |
| `HermesWatchdog` | JS hang detection, reload triggers |
| `HermesCrash` | ACRA crash reports |

### Startup Banner
```kotlin
Log.d(TAG, """
    ═══════════════════════════════════════
    Family Dashboard v${BuildConfig.VERSION_NAME}
    DASHBOARD_URL=${BuildConfig.DASHBOARD_URL}
    AUTH_TOKEN_PRESENT=${authToken.isNotEmpty()}
    BUILD_TYPE=${BuildConfig.BUILD_TYPE}
    FIRE_OS=${Build.VERSION.SDK_INT} (API ${Build.VERSION.SDK_INT})
    DEVICE=${Build.MODEL}
    WEBVIEW_VERSION=${WebView.getCurrentWebViewPackage()?.versionName}
    ═══════════════════════════════════════
""".trimIndent())
```

### ACRA Configuration
- Report format: JSON over HTTP POST to Telegram Bot API
- Report content: exception type, stack trace, device model, Fire OS version, app version, stack trace, last 50 log lines
- Trigger: any uncaught exception or ANR
- Rate limit: max 1 report per 5 minutes per app session

---

## Signing & CI/CD Design

### Build Variants
| Variant | Signing | Auth Token | WebView Debug | Minify |
|---------|---------|------------|---------------|--------|
| debug | v1+v2 (debug key) | empty string | enabled | off |
| release | v2-only (upload key) | from CI secrets | disabled | off |

### Release APK Signing Pipeline (GitHub Actions)
1. Build `app-release.apk` with `signingConfig = signingConfigs.getByName("debug")` (v1+v2)
2. `apksigner sign --v1-signing-enabled=false --v2-signing-enabled=true` with upload key
3. Verify: `apksigner verify -v --min-sdk-version 26` — must show `v1=false, v2=true`
4. Upload to VPS artifact path
5. Write version JSON to `version.json` on VPS
6. Send Telegram success notification

### Secrets (GitHub Actions)

> ⚠️ **Breaking change:** The CI pipeline now uses a dedicated **upload keystore** (`UPLOAD_KEYSTORE_*`) instead of the debug keystore. Set these new secrets before merging:

| Secret | Format | Description |
|--------|--------|-------------|
| `UPLOAD_KEYSTORE_B64` | Base64-encoded JKS or PKCS12 keystore | Keystore for v2-only APK signing |
| `UPLOAD_KEYSTORE_ALIAS` | String | Key alias inside the keystore |
| `UPLOAD_KEYSTORE_PASSWORD` | String | Keystore password |
| `UPLOAD_KEY_PASSWORD` | String | Private key password |
| `DASHBOARD_AUTH_TOKEN` | String | Auth token for X-App-Auth header (CI injects into BuildConfig) |
| `TELEGRAM_BOT_TOKEN` | String | Telegram bot token for build notifications |
| `TELEGRAM_CHAT_ID` | String | Telegram chat ID for build notifications |
| `VPS_HOST` | String | VPS hostname |
| `VPS_USER` | String | VPS SSH username |
| `VPS_SSH_KEY` | String | VPS SSH private key (base64) |

The old `DEBUG_KEYSTORE_B64` secret is no longer used.

---

## Dependency Changes

### Added
| Library | Version | Purpose |
|---------|---------|---------|
| `ch.acra:acra-mail` or `acra-http` | 5.11.0+ | Crash reporting |
| `androidx.lifecycle:lifecycle-process` | 2.7.0 | Process lifecycle observer for ACRA |

### Removed
| Library | Reason |
|---------|--------|
| None removed | — |

### Updated
| Library | Old | New | Reason |
|---------|-----|-----|---------|
| `androidx.webkit:webkit` | 1.8.0 | 1.10.0 | Security fix, Fire OS 7 WebView compatibility |

---

## File Manifest

```
app/src/main/
├── AndroidManifest.xml              # Permissions, KeepAwakeService, exported=false on service
├── java/com/hermes/firetv/
│   ├── DashboardActivity.kt          # Complete rewrite: SSL bypass removed, watchdog, error overlay, JS interface
│   └── KeepAwakeService.kt          # Remove fake touch, SCREEN_BRIGHT_WAKE_LOCK, no daemon thread
├── res/
│   ├── layout/activity_main.xml     # Unchanged
│   ├── xml/
│   │   └── network_security_config.xml  # NEW: block cleartext, trust system CAs
│   └── values/
│       ├── strings.xml              # Unchanged
│       ├── colors.xml               # Unchanged
│       └── themes.xml               # Unchanged
└── app/build.gradle.kts             # Add networkSecurityConfig, add ACRA deps, update webkit

.gradle/
└── (unchanged)

.github/workflows/build.yml          # Add set -e, fix ANDROID_HOME path, add auth token injection

gradle.properties                    # Add dashboardAuthToken placeholder
```

---

## Kiosk Mode Notes (Future Work)

Full kiosk lockdown (Device Owner / DPC) requires:
1. Factory-reset the Fire TV to set the app as Device Owner
2. Use `DevicePolicyManager` to lock to single app
3. Disable the camera, microphone, and ability to install other apps

This PRD does **not** include full kiosk lockdown. It includes:
- `FLAG_SECURE` (blocks screenshots)
- Back key suppression (prevents accidental exit)
- `allowBackup=false` (prevents data extraction)

Full kiosk lockdown should be added in a future iteration if the device will be in a public-facing location.

---

## Test Plan

### Pre-Deploy Tests (must pass before production deploy)
1. `adb install` release APK on Fire OS 6 device → Success
2. `adb install` release APK on Fire OS 7 device → Success
3. `apksigner verify -v --min-sdk-version 26 app-release.apk` → `v1=false, v2=true`
4. Launch app → dashboard loads within 15s
5. Disconnect network → error overlay shown within 10s
6. Reconnect network → auto-reload within 30s
7. Leave running 8 hours overnight → still visible in morning
8. `adb logcat | grep HermesDashboard` → no FATAL or FException
9. Check for auth token in decompiled APK → not present

### Smoke Test Matrix (from audit)

| Test | Pass Criteria |
|------|--------------|
| Fresh install (Fire OS 6) | `adb install` returns `Success` |
| Fresh install (Fire OS 7) | `adb install` returns `Success` |
| Cold boot launch | Dashboard loads within 15s |
| Idle 30 min | Screen stays on |
| Idle 8 hours | Dashboard still visible |
| Network drop | Error overlay within 10s |
| Network reconnect | Auto-reload within 30s |
| Remote: back button | Does not exit app |
| Remote: × button | Shows exit confirmation dialog |
| Crash (force-kill via adb) | App restarts (if launched from launcher) |
| adb logcat after crash | ACRA report sent to Telegram |

---

## Rollback Plan

If the release build causes regressions:
1. Revert `build.yml` commit → rebuild previous APK from git tag
2. Use previous `app-release.apk` artifact from GitHub Actions artifacts (30-day retention)
3. Sideload previous APK via `adb install -r <old-apk>.apk`
4. VPS `version.json` points to old APK URL — no rollback needed there

---

## Open Questions

1. **TTV WebView:** Should we migrate to `com.amazon.webview.chromium` (Amazon's TV WebView) instead of the standard Android WebView for better long-term stability on Fire OS?
2. **Auth sidecar:** The auth sidecar at `127.0.0.1:9080` currently handles auth. Should the WebView go direct to the dashboard and inject the token at the app layer (current approach), or should it go through the sidecar? Current approach is correct for a WebView app.
3. **Health endpoint:** Should the dashboard server expose a `GET /api/health` endpoint that returns `{ "ok": true, "uptime": N }` for the app to poll?
4. **Crash reporting frequency:** Is 1 Telegram notification per 5-minute crash window acceptable, or should it be throttled more aggressively?
5. **Offline mode:** Should the dashboard cache the last known good HTML/JS bundle to local storage and serve it when offline? Or is showing the error overlay acceptable?
