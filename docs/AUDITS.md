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

## v1.0.4 — crash containment + Media3 H.265 fix (2026-11-01)
Field crash: on the W2, house-4's HEVC sub-stream SPS crashed Media3
1.4.1's parser (uncaught ArrayIndexOutOfBounds on the RTSP thread,
app dead <1 s after every launch; reports in the W2's Downloads).
Change-impact: (1) Media3 1.4.1 → 1.8.1 (parser hardening line;
compileSdk/targetSdk 34 → 35, build-tools 35 installed into the local
SDK); no new permissions; no UI changes. (2) New SpsGuard pre-flight:
one extra RTSP DESCRIBE per stream start (the same request the player
itself makes), SPS parsed on a controlled thread; a vetoed stream
never starts a player. (3) Crash handler: background-thread media3
failures are reported and contained, not chained to the system
killer; main-thread and non-media failures keep the normal path.
(4) Carries the v1.0.3 edit-screen LayoutParams fix from the tree.
Blast radius: this app only. Rollback: reinstall 1.0.3 (config format
unchanged). Tests: 12/12 unit tests incl. SpsGuard SDP parsing, a
genuine x265 SPS parse, and a truncation fuzz proving the guard never
throws. Live-stream parser verification: W2 launch after install
(Mechanic lane) — house-4 tile Live proves it end-to-end; an error
tile with the app alive proves containment instead.

## v1.0.5 — house-4 SPS verdict + photo fallback (2026-11-01)
Bytes-level verification with house-4's REAL sprop values (Mechanic
lane DESCRIBE): replicating RtspMediaTrack's exact parser call in a
JVM harness, Media3 1.4.1 reproduces the W2 crash precisely
(ArrayIndexOutOfBounds 41/41) — and 1.8.1, 1.9.4 and 1.11.1 throw the
IDENTICAL exception. No released Media3 parses this SPS; the bug is
open upstream. Conclusions: (1) the SpsGuard is the fix for this
stream, and it now replicates the player's call faithfully
(start-code prepend, offset 4, vps=null) — regression test with
house-4's real SPS added (guard must veto; a standard x265 SPS must
pass). (2) A vetoed stream now degrades to the ladder's last resort —
snapshot photo polling via the family template — instead of only an
error tile (LAN only; relay serves no snapshots). (3) Media3 stays at
1.8.1 (newer lines verified not to fix this and would force a
compileSdk-36 cascade for nothing).

## v1.0.7 — snapshot fallback v2 (2026-11-01)
W2 field result on 1.0.5: house-4 photos failed — /snapshot.jpg is
404 on this camera; its snapshot endpoints (/snap.jpg,
/tmpfs/auto.jpg, /cgi-bin/snapshot.cgi, /cgi-bin/media.cgi?...)
answer 401 (the fleet's HTTP login; RTSP stays anonymous). Fallback
now rotates candidate paths (template → verified fleet paths →
generic library), answers Basic/Digest challenges with the tile's
stored credentials (new core/HttpFetch; RFC-anchored unit tests),
remembers the winning path, and — when the endpoints demand a login
and none is stored — the tile says so plainly with the remedy
(press-and-hold → add the camera password) instead of a generic
failure. Carries the 1.0.6 leanback fix unchanged.
