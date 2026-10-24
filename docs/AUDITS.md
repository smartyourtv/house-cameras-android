# Audits — House Cameras (Android)

## Change-impact audit (2026-10-24, before implementing)
1. **Blast radius** — Brand-new standalone app, new repo
   (`smartyourtv/house-cameras-android`). Touches no existing code,
   no ThinkPad files, no panel, no other app. Shared inputs are
   read-only: Windows DESIGN/AUDITS, requirements §1, 2026-10-24 census.
2. **Regression risk** — None: no shared code modified. Build reuses the
   Scout Android toolchain paths/settings pattern, copied not edited.
3. **Cross-build effects** — None. Sibling to the Windows app by design
   (same templates/ladder/seed); no code shared at runtime.
4. **Data & compatibility** — One new app-private data location.
   Seed carries addresses only, no credentials. No existing data read.
5. **Permissions & security** — INTERNET, ACCESS_NETWORK_STATE,
   ACCESS_WIFI_STATE, CHANGE_WIFI_MULTICAST_STATE (discovery),
   REQUEST_INSTALL_PACKAGES (updater, user-tapped only). No location,
   no mic, no camera. Credentials Keystore-encrypted, never logged,
   URLs redacted in logs/reports. Relay mode talks only to the
   user-entered Tailscale relay host with a session token.
6. **Behavior changes** — Nothing existing changes.
7. **Performance** — Sub-streams in grid; one player per visible camera;
   players released when backgrounded; snapshot polling rate-limited.
8. **Rollback** — Uninstall removes app + its private data. Nothing else
   on the phone changes.
9. **Test plan** — Unit tests (templates, path library, ladder ordering,
   store repair, URL redaction, relay URL building); self-test on device
   without live cameras; signed release build; APK verification
   (signature, package, version). Live-camera acceptance is a separate
   step coordinated through main, after the relay verifies.

## Pre-build audit (2026-10-24, before building) — COMPLETED
1. **Permissions** — Manifest declares only INTERNET, ACCESS_NETWORK_STATE,
   ACCESS_WIFI_STATE, CHANGE_WIFI_MULTICAST_STATE (discovery, user-started),
   REQUEST_INSTALL_PACKAGES (updater, user-tapped). No location/mic/camera/
   storage permissions. MulticastLock is acquired only during a user-started
   discovery run and released in a finally block.
2. **Null-safety / error handling** — All network probes are exception-
   contained and return false/empty on failure; config parse failure routes
   to the verified self-repair path; player errors surface as a plain-language
   "No signal" status, never a crash. Crash handler installed in MainActivity.
3. **API misuse** — Network work runs on named background threads, never the
   UI thread; UI updates via runOnUiThread / main-Looper handler. Players are
   released in onPause (grid + single). Snapshot polling is interrupt-aware.
   MediaStore Downloads insert is API-29-gated with a legacy fallback.
4. **Protocol contracts** — Template table and generic path library are
   byte-identical in values to the Windows sibling; ingest ladder order
   matches spec §1.2 (ONVIF → template → generic → MJPEG → snapshot);
   relay URL scheme is the single documented deviation, isolated in
   `Camera.streamUrl` + RelayConfig, pending relay-lane confirmation.
5. **Security** — Passwords and the relay session token are Android
   Keystore AES-GCM encrypted at rest; stream URLs are built in memory
   only; `redact()` is applied to every crash-report stack trace; no
   credential appears in any log call (there are no URL log calls);
   allowBackup=false so credentials are not in cloud backups.
   usesCleartextTraffic=true is required for LAN camera RTSP/HTTP and is
   documented — traffic targets are the user's own LAN cameras / relay.
6. **Dependency/version check** — AGP 8.12.3, Kotlin 1.9.0, Media3 1.4.1
   (exoplayer + rtsp + ui), AndroidX core-ktx 1.13.1, JUnit 4.13.2 —
   all resolved and unit tests pass (8/8, testDebugUnitTest BUILD
   SUCCESSFUL 2026-10-24). compileSdk/targetSdk 34, buildTools 34.0.0
   (the local SDK has no 35 build-tools; recorded as an environment
   deviation, no user-facing effect).
Result: PASS — proceed to signed release build.
