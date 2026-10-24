# House Cameras (Android) — Design

Owner order: Sat 2026-10-24 16:35 PDT via main chat — "an Android version
of the remote camera viewing software in the same fashion" as the Windows
version. Foundation: `~/workspace/house-cameras-windows/docs/DESIGN.md` +
`docs/AUDITS.md`, governed by spec §1 of
`goals/kitt-van-voice-ai-build/hidden_files/house-camera-rewrite-requirements.md`
(maximum camera diversity).

- Display name: **House Cameras** · package `com.smartyourtv.housecameras`
- Repo: `smartyourtv/house-cameras-android` · APK `HouseCameras-latest.apk` (stable name)
- v1.0.0 (versionCode 100)

## Carried over unchanged from the Windows design
- **One core + per-family templates** — hip2p, reolink, dahua, hikvision,
  foscam, xmeye, tapo, generic. New family = new template entry, never a rewrite.
- **Discovery, all four, merged:** ONVIF WS-Discovery (UDP 3702 multicast),
  HiP2P VSCP broadcast (`SEARCH * HDS/1.0`), Reolink identification
  (TCP 9000 / GetDevInfo API), plain LAN sweep (554/80/8000/8899/9000,
  RTSP OPTIONS + HTTP banner; unknown hosts reported, never interrogated).
- **Ingest ladder, in order, first *verified decoded picture* wins:**
  ONVIF → vendor template → generic RTSP known-path probing →
  MJPEG → JPEG snapshot polling (last resort). Winning source remembered.
- **Codecs:** H.264, H.265/HEVC, MJPEG (house-4 main is HEVC 2304×1296).
- **Honest limit, stated in-app:** closed cloud-only cameras (Ring, Blink,
  stock Wyze, Nest) expose no local stream and cannot be added.
- **Viewing:** grid (sub-stream) + single view (main stream), each picture
  as large as its box allows, true shape, **no stretch, no crop** (letterbox).
  Per-camera status: Live / Connecting / No signal + age of last good frame
  (freshness, not process state).
- **Seed (addresses only, NO credentials):** house-1 192.168.40.25,
  house-2 .82, house-3 .131, house-4 .218, house-5 .233, house-6 .240
  (HiP2P, /11 main · /12 sub), house-7 Reolink placeholder, disabled —
  identified by the 2026-10-24 house census as the device registering
  hostname "Front", at 192.168.40.150 at census time (address may move
  until DHCP reservations land); seeded with that address, still
  disabled until its password is entered on the phone.
- **Credentials:** entered on the phone in the app only; never logged
  (host+path only, URLs redacted), never in repo/chat, never in a child
  process command line. Stream URLs built in memory at connect time.
  Credentials object separate from stream description (audit item 2).
- **Standing laws:** diagnostics/status view + one-tap self-test; crash
  reports in-app AND `HouseCameras-crash-<YYYYMMDD-HHMMSS>.txt` in
  Downloads (synchronous at crash, never auto-uploaded); conservative /
  verified / logged self-repair of own state; updater (GitHub check,
  one subtle notice per version, tap to apply, SHA-256 verified);
  plain-language UI; no on-screen keyboard beyond the system one the
  user invokes for text fields.

## Android deviations (so the two apps stay siblings, not strangers)
1. **UI toolkit:** Tkinter → native Android Views, programmatic layouts
   (no Compose) — keeps the APK small and the build dependency-light.
2. **Decode engine:** OpenCV/FFmpeg → **Media3 ExoPlayer** (RTSP, H.264/
   HEVC/MJPEG). Snapshot/MJPEG layers use HttpURLConnection + BitmapFactory.
3. **Credential storage:** Windows DPAPI → **Android Keystore AES-256-GCM**;
   each password is encrypted with a Keystore key that never leaves the
   device, stored as Base64(iv+ciphertext) in the app-private config.
4. **Crash file location:** Downloads via MediaStore (API 29+), falling
   back to the public Downloads directory on older APIs.
5. **Discovery permissions:** multicast needs a WifiManager MulticastLock
   + `CHANGE_WIFI_MULTICAST_STATE`; discovery is user-started, never a
   background service.
6. **Remote viewing is CORE here (Windows v1 was LAN-only):** two source
   modes — **At home (LAN direct)** and **Away (relay)**. In Away mode the
   app never talks to a camera directly; it talks only to the ThinkPad
   relay at its Tailscale address, presenting the per-session token the
   relay issues. Relay stream path scheme (to be confirmed with the
   Enterprise Security lane via main before on-camera acceptance):
   `rtsp://<relay-host>:<port>/<camera-id>` with token auth, relay stopped
   at rest. Until the relay interface is confirmed, Away mode is present
   in settings/UI with the host/token fields and clearly labelled
   "relay not connected yet" — it does not block LAN v1.
7. **Updater install:** download to app cache, SHA-256 verified, then the
   system installer via FileProvider (user taps Install) — Android does
   not allow silent installs for sideloaded apps.
8. **Battery:** grid pauses when the app is backgrounded/hidden; sub-streams
   in grid keep decode load and data use low. (Windows was mains-class.)

## Out of scope for v1
Recording/DVR, PTZ (template fields reserved), two-way talk,
PPPP/PPCS LAN-direct leg (only if the queued ThinkPad test proves it).

## Coordination
No live-camera load from this lane beyond ordinary viewing pulls.
On-camera acceptance sequences through main: relay verifies first, then
the viewer apps one at a time. Nothing lands on the ThinkPad. The camera
internet block is held by owner decision — not a gate for this build.
