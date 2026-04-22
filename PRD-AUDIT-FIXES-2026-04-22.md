# PRD — Fire Stick Dashboard Audit Fixes
**Created:** 2026-04-22
**Source audit:** `./AUDIT-2026-04-22.md` (same directory)
**Owner:** Hermes agent
**Related PRDs:**
- `/home/firetv-dashboard/PRD.md` (pre-existing production-readiness PRD — this doc complements it)
- `/home/assistant-stack/PRD.md` (backend/stack PRD)

---

## Problem Statement

An external audit on 2026-04-22 found that the Fire Stick wall dashboard is broken in production. The root cause is **not** bad code — the source tree is largely correct. The root cause is **deployment and release drift**:

1. The live Next.js app at `/opt/data/home/assistant-stack/dashboard/` is a stale snapshot. Its `/api/refresh` endpoint does NOT return `unscheduled_events`, but `page.tsx` assumes it does and calls `.length`/`.map` on it, producing a `TypeError` on every client render. With no React error boundary, the UI blanks. This is the "dashboard disappears" symptom the user has been reporting.
2. The shipped APK `public/apk/firetv-v1.8.1.apk` was built from an older commit. It contains the old `DashboardActivity$sslTrustManager$1` + `sslSocketFactory$2` classes, `usesCleartextTraffic="true"`, no `BootReceiver`, no `RECEIVE_BOOT_COMPLETED` permission — all of which have **already been fixed** in `/home/firetv-dashboard/app/src/main/`. It also has `versionCode=1`, so every subsequent install is rejected as a downgrade or silently no-ops.
3. The release pipeline falls back to `versionCode=1` when `CI_BUILD_NUMBER` is unset (`app/build.gradle.kts:31`). Any local `./gradlew assembleRelease` produces an APK with versionCode=1, guaranteeing install collisions.

## Root Cause

Stale deploy + stale APK + unreliable versionCode injection — not missing features.

## Proposed Fix (phases)

- **Phase 1 — Unblock production (today):** sync the live Next.js deploy with the Hermes source; rebuild and sideload a new APK from the existing source with a real versionCode.
- **Phase 2 — Lock down the release pipeline (this week):** make local builds produce a monotonically increasing versionCode; add a deploy script that syncs `/home/assistant-stack/dashboard/` → `/opt/data/home/assistant-stack/dashboard/`.
- **Phase 3 — Hardening (next week):** error boundary, crash telemetry, prominent stale-data indicator, auth gate on `/`, screensaver defeat, smoke-test matrix on Fire Stick 4K Gen 1.

## Impact If Not Fixed

- Family wall dashboard stays blank until someone manually refreshes the WebView.
- Any new APK you build cannot be installed as an upgrade (versionCode=1) — must uninstall first, losing any stored WebView state.
- LAN MITM is possible via the shipped APK's TrustManager bypass. Let's Encrypt cert rotation could silently break and nobody would notice.
- No auto-launch after Fire Stick reboot (shipped APK has no BootReceiver, even though source does).
- No crash visibility from the field.

## Out of Scope (for this PRD)

- Migrating to Fully Kiosk Browser (covered as an alternative in the audit; explicitly out of scope here — we're keeping the custom APK).
- Next.js 16 → 15 / React 19 upgrade (mentioned in audit but is Phase 3 tech-debt; doesn't block production).
- Amazon App Store submission hardening (out of scope for sideload-only kiosk).
- Kiosk lockdown / DPC / device owner enrollment — out of scope; tracked in `PRD.md`.
- Adding event-archive functionality beyond what source already has.

---

## Success Criteria

- [ ] `curl http://127.0.0.1:8081/api/refresh | jq keys` lists `unscheduled_events`.
- [ ] A Fire Stick browsing `https://dashboard.cashlabnyc.com` paints real calendar content within 10 seconds (no stuck "Loading…", no blank screen after the first fetch).
- [ ] `adb shell dumpsys package com.hermes.firetv | grep versionCode` reports a value >1 on every new build, incrementing each build.
- [ ] `aapt dump xmltree <apk> AndroidManifest.xml` for the next released APK shows: `usesCleartextTraffic="false"`, `allowBackup="false"`, presence of `BootReceiver` with `BOOT_COMPLETED` intent-filter, `RECEIVE_BOOT_COMPLETED` permission declared, `foregroundServiceType="specialUse"` on `KeepAwakeService`.
- [ ] `unzip -p <apk> classes*.dex | strings | grep -i sslTrustManager` returns nothing.
- [ ] A JS runtime error in the client UI renders a recovery card (e.g. "Dashboard paused, retrying…") and automatically reloads within 60s, rather than blanking the page.
- [ ] `/api/telemetry/crash` endpoint exists and is hit from both Kotlin (`UncaughtExceptionHandler`) and browser (`window.onerror` + `window.onunhandledrejection`).
- [ ] Stale-data indicator on the dashboard is readable from 10 feet; turns yellow at >5min stale, red at >30min.
- [ ] After Fire Stick reboot, `DashboardActivity` resumes automatically (via `BootReceiver`).
- [ ] Traefik's `dashboard-https` and `static-assets` routers both list `auth-check` in `middlewares`.
- [ ] Smoke tests #1-#12 in the audit's SECTION 6 all pass on a Fire Stick 4K Gen 1.

---

## Architecture Summary (for context)

```
Fire Stick (APK = com.hermes.firetv)
  └── DashboardActivity (WebView)
        └── https://dashboard.cashlabnyc.com/
              │
              ▼
        Traefik (port 443, letsencrypt)
          ├── /              → Next.js :8081
          ├── /_next/...     → Next.js :8081
          ├── /api/...       → Next.js :8081  [auth-check middleware]
          ├── /apk/...       → apk-static-svc :9081
          └── /debug         → auth-sidecar :9080
              │
              ▼
        Next.js standalone :8081
          └── reads /opt/data/home/assistant-stack/calendar/{local_calendar,tasks}.json
```

Two Next.js source trees exist:
- `/home/assistant-stack/dashboard/` — Hermes-owned source (has the fixes)
- `/opt/data/home/assistant-stack/dashboard/` — deploy target (stale; what's actually serving)

The APK source:
- `/home/firetv-dashboard/` — Hermes-owned source (has the fixes)
- `/opt/data/home/assistant-stack/dashboard/public/apk/firetv-v1.8.1.apk` — deploy target (stale; what users download)

---

## TODO (priority order)

### Phase 1 — Unblock production (today)

- [ ] **P1-01 (P0)** — Diff `/home/assistant-stack/dashboard/src/app/api/refresh/route.ts` vs `/opt/data/home/assistant-stack/dashboard/src/app/api/refresh/route.ts` to confirm the drift. The Hermes-side file is the correct one.
- [ ] **P1-02 (P0)** — Sync Hermes-side Next.js source to the deploy target:
      `rsync -av --delete /home/assistant-stack/dashboard/src/ /opt/data/home/assistant-stack/dashboard/src/`
      Also sync `public/`, `next.config.ts`, `package.json`, `tsconfig.json`. Do NOT sync `.next/` or `node_modules/` — those should be rebuilt on the target.
- [ ] **P1-03 (P0)** — Rebuild on target: `cd /opt/data/home/assistant-stack/dashboard && npm ci && npm run build`. Verify `.next/standalone/server.js` exists.
- [ ] **P1-04 (P0)** — Restart the Next.js process (it's currently PID 298066, root-owned, running from `.next/standalone`). Cleanest: `kill` it and relaunch with the same env (`PORT=8081 HOSTNAME=127.0.0.1`).
- [ ] **P1-05 (P0)** — Verify: `curl -sS http://127.0.0.1:8081/api/refresh | jq 'keys'` should include `unscheduled_events`. Then `curl -sS https://dashboard.cashlabnyc.com/ | grep -c "Family Dashboard"` should be non-zero (after an authed cookie, or temporarily remove auth-check to test).
- [ ] **P1-06 (P0)** — Add a defensive client-side default for `unscheduled_events` so this class of bug can't blank the screen again:
      In `/home/assistant-stack/dashboard/src/app/page.tsx` around line 499, change
      ```
      const { today, today_events, week_events, upcoming_tasks, unscheduled_events } = data
      ```
      to
      ```
      const { today, today_events, week_events, upcoming_tasks } = data
      const unscheduled_events = data.unscheduled_events ?? []
      ```
      Deploy after P1-02.
- [ ] **P1-07 (P0)** — Rebuild APK from `/home/firetv-dashboard/` with a real versionCode. Workflow:
      1. Decide on versionCode scheme. Simplest: `versionCode` = seconds-since-epoch / 60 (unique, monotonic). Or set `CI_BUILD_NUMBER` in `gradle.properties` and increment it each build.
      2. Temporarily set `gradle.properties` → `CI_BUILD_NUMBER=11` (next after the `build: 10` claim in public/version.json) and `dashboardAuthToken=<real token from /home/firetv-dashboard/firetv-secrets.txt or wherever it lives>`.
      3. `cd /home/firetv-dashboard && ./gradlew assembleRelease`.
      4. `apksigner verify -v app/build/outputs/apk/release/app-release.apk` — confirm v2 signature.
      5. `aapt dump badging app/build/outputs/apk/release/app-release.apk | head -5` — confirm `versionCode='11'`.
      6. `unzip -p .../app-release.apk classes*.dex | strings | grep -i sslTrustManager` — expect empty.
- [ ] **P1-08 (P0)** — Publish the new APK to the deploy target:
      `cp app/build/outputs/apk/release/app-release.apk /opt/data/home/assistant-stack/dashboard/public/apk/firetv-v1.8.<build>.apk` and update `/opt/data/home/assistant-stack/dashboard/public/version.json` with the matching version/build/apk_url.
- [ ] **P1-09 (P0)** — Sideload to at least one Fire Stick 4K Gen 1 and one Gen 2:
      `adb connect <ip>:5555 && adb uninstall com.hermes.firetv && adb install firetv-v1.8.<build>.apk && adb shell am start -n com.hermes.firetv/.DashboardActivity`
      Verify the dashboard paints real content within 10s.

### Phase 2 — Lock the release pipeline (this week)

- [ ] **P2-01 (P1)** — Make `versionCode` auto-increment on local builds too (so nobody ever ships versionCode=1 again). Option A: script that reads/writes `gradle.properties` before each build. Option B: derive from `git rev-list --count HEAD`. Option C: use `System.currentTimeMillis() / 60000` as versionCode. Pick one, document in the APK PRD.md.
- [ ] **P2-02 (P1)** — Add a deploy script `/home/firetv-dashboard/scripts/deploy.sh` or similar that:
      1. Syncs Next.js source to `/opt/data/home/assistant-stack/dashboard/`.
      2. Rebuilds `.next/standalone`.
      3. Graceful-restarts the Next server.
      4. Rebuilds the APK with incremented versionCode.
      5. Copies APK into `public/apk/` and updates `version.json`.
      6. Prints a checksum + versionCode for audit trail.
- [ ] **P2-03 (P1)** — Convert the Next.js process to a systemd unit (see audit §4.1 Patch E) so it auto-restarts on crash. Runs as `hermes` user, not root.
- [ ] **P2-04 (P1)** — Traefik: add `middlewares: [auth-check]` to `dashboard-https` and `static-assets` routers in `/etc/traefik/dynamic.yml`. Verify auth-sidecar at `:9080` passes cookie/header from Fire TV WebView.
- [ ] **P2-05 (P1)** — Remove Fire Stick's fallback IP from DEX: confirm the current `DashboardActivity.kt` in source has no `http://2.24.198.162` literal. Audit found this string in the shipped APK; the source may have already dropped it — verify with `grep -r "2.24.198" /home/firetv-dashboard/app/src/`.
- [ ] **P2-06 (P2)** — Delete unused files from dashboard/: `server.py`, `Dockerfile`, `docker-compose.yml`, empty files `family-dashboard@1.0.0` and `next`. They serve no purpose and mislead anyone who tries `docker compose up`.
- [ ] **P2-07 (P2)** — Pin `package.json` to a known-good combo: either `next@15.5.4 + react@18.3.1 + typescript@5.6.3` (safe) or `next@16.x + react@19.x + typescript@5.6.3` (aligned with Next 16 peer deps). `typescript: 6.0.3` must go — it doesn't exist.

### Phase 3 — Hardening (next week)

- [ ] **P3-01 (P1)** — Add React error boundary. Create `/home/assistant-stack/dashboard/src/app/error.tsx` and `global-error.tsx` per Next.js App Router docs. On error, show "Dashboard paused, retrying…" and schedule a `window.location.reload()` after 30s. This prevents any future field-shape mismatch from blanking the screen.
- [ ] **P3-02 (P1)** — Add crash telemetry endpoint `POST /api/telemetry/crash` that appends to a JSON lines file in the calendar dir. Rate-limit obvious abuse. Then wire:
      - Kotlin side: `Thread.setDefaultUncaughtExceptionHandler { _, e -> HttpURLConnection.POST stack → restart via AlarmManager → Process.killProcess }` in `FireTVApplication.kt`.
      - JS side: `window.addEventListener('error', ...)` + `unhandledrejection` in `layout.tsx` that POSTs to the endpoint.
- [ ] **P3-03 (P1)** — Make the "last updated" timestamp prominent. Change `page.tsx:565` color from `#334155` to something readable; turn the whole freshness chip yellow at age > 5min, red at > 30min. Display age in human units ("2m ago", "18m ago", "STALE — 1h 12m").
- [ ] **P3-04 (P1)** — Verify `onRenderProcessGone` is wired in `/home/firetv-dashboard/app/src/main/java/com/hermes/firetv/DashboardActivity.kt`. Audit found no matching string in the shipped APK's DEX, but DashboardActivity.kt is 764 lines so it may already exist in source. If not, add it — returning `true` and rebuilding the WebView. Audit §3.2 has code sketch.
- [ ] **P3-05 (P1)** — Verify `onReceivedError` is implemented with reload + exponential backoff. `DashboardActivity.kt:352` appears to have the handler; check that it actually schedules a retry.
- [ ] **P3-06 (P2)** — Screensaver defeat on Fire Stick: either a periodic synthetic keypress every 4 min, OR write clear operator docs telling the user to set Settings → Display → Screensaver → Never. Recommend both (belt and suspenders).
- [ ] **P3-07 (P2)** — Add `FOREGROUND_SERVICE_SPECIAL_USE` permission to `AndroidManifest.xml`. Source currently declares only `FOREGROUND_SERVICE`; targetSdk 34 requires the special-use sub-permission to match the service's `foregroundServiceType`.
- [ ] **P3-08 (P2)** — Check `apksigner verify -v` on the current APK. If v1+v2 dual-signed, switch to v2-only to fix Fire OS 5 install issues (per existing `PRD.md` success criteria).
- [ ] **P3-09 (P2)** — Move calendar data fetch from client to a Server Component so the first paint has real data (audit §3.4). Keep the 30s client-side poll.
- [ ] **P3-10 (P2)** — Add a `/status` or `/debug/health` page that dumps `/api/refresh` and timestamp — lets operators verify the pipe end-to-end from any browser without the WebView.
- [ ] **P3-11 (P3)** — Migrate Dockerfile/compose to build the real Next.js standalone image (or delete them entirely — decided in P2-06).

### Verification

- [ ] **V-01** — Run the smoke test matrix (audit §6, 12 scenarios) on Fire Stick 4K Gen 1 after Phase 2 completes. Track pass/fail in this PRD. Any failure becomes a new todo.
- [ ] **V-02** — Overnight soak test: leave one Fire Stick on the dashboard 12 hours. Check `dumpsys meminfo` heap, WebView renderer PID stable, no ANR in `/data/anr/`, first-paint timestamp updated.
- [ ] **V-03** — Failure-injection: (a) stop the Next.js service for 3 min → verify recovery banner + reload, (b) `adb shell am crash com.hermes.firetv` → verify Activity restart, (c) disable Wi-Fi for 2 min → verify no blank + reconnect.

---

## Notes for the Hermes agent

- **Don't rewrite.** The source is mostly right. Ship it.
- **Verify before coding.** For each TODO, first `grep` / `diff` to confirm the assumption (audit was based on static analysis; a Kotlin decompiler was not available). If a fix is already in source, skip to "build + deploy + verify."
- **Keep the two source trees in sync.** After each change, make sure `/home/assistant-stack/dashboard/` (Hermes source) and `/opt/data/home/assistant-stack/dashboard/` (deploy) agree. The deploy script from P2-02 should make this automatic.
- **Touch the live environment carefully.** The Next.js process is running as root on :8081 — don't drop connections without a plan. Run the deploy script against a staging port first if possible.
- **Use `todo` for cross-session state.** This PRD has too many items for MEMORY.md. Track by copying the TODO checkboxes into the `todo` tool with IDs like `f1-01` through `f3-11`.
- **Update `PRD.md` (the main APK PRD) when Phase 3 items land** — this file is a focused audit-response; `PRD.md` is the long-term production-readiness doc.

---

## Memory pointer

To save in `MEMORY.md`:
```
- FireStick audit 2026-04-22: stale deploy + stale APK. Source OK. Full audit + PRD at /home/firetv-dashboard/{AUDIT,PRD-AUDIT-FIXES}-2026-04-22.md
```
