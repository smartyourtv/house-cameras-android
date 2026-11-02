# House Cameras v2 — "done right once" (DESIGN, 2026-11-02)

Owner decision 2026-11-02: stop fiddling with the current viewer;
rebuild camera viewing as proper Android software. ONE app for
regular Android AND Android TV (single build). Target: installed and
proven on the garage W2 before Dad arrives (Nov 16/17). This document
is the design; the build starts when Main coordinates it against the
plate. The road itself (garage uplink) is NOT this build — the app
must be honest about road quality instead of pretending.

## Why (the failures this design makes impossible or honest)

1. house-5 was lost when a power-cycle shuffle moved it off its
   recorded address (.231): the app trusted raw addresses.
2. house-4's HEVC stream crashes Media3's SPS parser on every
   released version (proven to 1.11.1): handled today by guard +
   containment, but the layering grew by patches.
3. The W2's road (spare-RE200 hop) stalls and wedges: the old app
   reconnect-looped and the viewer flashed; nothing measured or
   reported the road, so "camera problem / app problem / road
   problem" was guesswork for a whole day (2026-11-01).
4. TV launch was an afterthought once already (LEANBACK missing
   until v1.0.6): TV is a first-class target from line one this time.

## Architecture (same repo/package; v2.0.0)

Kept (field-proven in v1.0.x): encrypted config store + self-repair,
per-family templates, SpsGuard (player-faithful), HttpFetch photo
fallback v2, crash/error reports (in-app + Downloads file, never
uploaded), updater (notice-once, tap to apply), ingest ladder logic,
discovery transports (extended, see below).

Replaced: PlayerHelper (per-tile player with ad-hoc retries) →
StreamSupervisor engine. MainActivity ad-hoc grid → Stage UI.

New: IdentityStore + Resolver, RoadMeter, BootReceiver, global
circuit breaker.

### 1. Cameras are found by IDENTITY, never by address alone

Per camera, config stores an identity block (addresses are a cache,
not the truth):

- burned-in MAC, plus any presented-MAC variants (the fleet presents
  a locally-administered prefix variant — recorded as an alias; the
  laptop registry proved identity by burned-in MAC tail + live frame)
- ONVIF UUID and ONVIF serial (from GetDeviceInformation) when seen
- HiP2P UID/DID when seen
- family + model hints, last-known address, last-verified timestamp

Resolution pipeline (at app start, and whenever a stream is
unrecoverable at its cached address):
1. FAST PATH — probe the cached address; require the device there to
   prove identity (ONVIF device-information match, or the family's
   identity-bearing response). A stranger at the address is NOT
   adopted (this is the failure that mislabeled cameras before).
2. SWEEP — ONVIF WS-Discovery broadcast, HiP2P VSCP broadcast
   (its reply carries IP + MAC + UID directly), Reolink ID probe,
   then a subnet probe of RTSP/HTTP hosts as last resort.
3. MATCH — exact MAC → MAC tail (last 3 octets + family) → ONVIF
   UUID → HiP2P UID, in that order. A candidate must prove identity
   end-to-end before any repoint: config is updated, the heal is
   logged to Reports ("house-5 moved .231 → .87, identity proven by
   MAC"), and the tile reconnects.
4. AMBIGUITY — two candidates matching: never guess. The tile shows
   "Found two possible matches — needs a look" and diagnostics
   lists both. Safety over eagerness (identity-hunt rule).

Note on platform limits: Android apps cannot reliably read the ARP
table on modern Android, so MAC proof comes from the discovery
protocols themselves (VSCP carries it; ONVIF gives UUID/serial and
its endpoint), not from /proc/net/arp.

### 2. StreamSupervisor — self-healing streams, honestly measured

One supervisor per tile, a small state machine:
RESOLVING → CONNECTING → LIVE → STALLED → RESTARTING → (LIVE | ERROR)

- Health is FRAME FRESHNESS (age of the last decoded frame), never
  process liveness — the standing freshness rule. A tile showing a
  frame older than ~6 s is STALLED, says so, and restarts itself.
- Self-repair law applies: restarts are capped (burst allowance,
  then a slow lane: 6 fast retries → 60 s cadence forever), every
  restart is logged with its reason, and no tile ever dies silently —
  ERROR is a state with a plain-words reason, not an ending.
- Global circuit breaker: if most tiles stall together, the app
  blames the road, not the cameras — all supervisors back off
  together and the grid banner says "Road is bad right now —
  streams will return on their own." No restart storms into a
  wedged hop (the 2026-11-01 grid-wide collapse is the case study).
- DEEP BUFFERING to ride out road stalls: grid view targets a
  10-second playback buffer (RTSP forced over TCP so loss becomes
  retransmit + latency, not holes); single view 4 seconds. Grid
  monitoring tolerates seconds of latency; the trade is stated on
  the diagnostics card, not hidden. Buffer sizes are settings,
  defaults as above.
- Direct pulls first: the ladder is ONVIF → family template →
  generic RTSP → photos. The laptop transcode relay is used ONLY
  where a camera cannot be pulled directly (today: house-4-class
  SPS-vetoed HEVC, when HQ's relay is configured); the tile and the
  diagnostics card say which source is in use — Direct / Relay /
  Photos — never silently.

### 3. RoadMeter — the road, measured and said plainly

Per camera, rolling stats: last-frame age, stall count and total
stall time (10-min window), reconnects, TCP connect time. Box-level
summary rolls up to one word on the diagnostics card and a small
unobtrusive header indicator: **Road: Good / Shaky / Bad**
(thresholds: Bad = any wedged-phase signature — repeated connect
refusals/timeouts; Shaky = stalls or >2% frame-gap loss in window;
else Good). The meter is what the 2026-11-01 hold test had to do by
hand; the app now keeps that evidence itself.

### 4. Stage UI (banked camera-stage pattern)

Stage first (tall, leading the layout), exact-fill grids, letterbox
inside tiles — no stretch, no crop:
- HOUSE — 3 wide × 2 tall (the six house cameras, all visible at
  once; replaces the v1 scrolling 2-wide grid the W2 suffered)
- DVR — one row of 4
- ALL — 5 wide × 2 tall (house + DVR together)
Views switch from a top bar; the last view is remembered.

TV behavior (first-class):
- LEANBACK_LAUNCHER + LAUNCHER in one manifest, TV banner/icon
  (the v1.0.6 fix, designed-in this time)
- Boot-straight-to-cameras: BOOT_COMPLETED receiver launches the
  grid on TV boxes; a settings toggle, default ON for TV, OFF for
  phones. (Android-version background-start limits vary by vendor
  build; the W2's behavior gets verified in the field phase, and
  "set as home app" HOME-category is the belt-and-braces option if
  a box refuses the boot launch.)
- D-pad everywhere: tiles are focusable with a visible focus frame;
  OK/center → fullscreen, Back → grid; settings, edit, diagnostics
  and reports all navigable by remote. Tap works identically on
  touch devices.

### 5. Diagnostics card v2 (standing rule: every app gets one)

Per camera row: name, state in plain words, source (Direct/Relay/
Photos), current address + the identity anchor that proved it,
last-frame age, road word, restarts this hour. Box section: Road
summary, view/buffer settings, app version, updater state. One-tap
SELF-TEST (extended): identity resolution dry-run per camera
(proves each camera is who its tile thinks it is), per-camera
stream probe, relay probe if configured. Reports view lists every
heal, restart storm, and crash — in-app and as the Downloads file.

### 6. Rules carried in, unchanged

Crash/error reports (in-app + Downloads, never auto-uploaded);
config self-repair (conservative, verified, logged); updater
(check, one subtle notice per version, tap to apply, SHA-256
verified); credentials on-device only, Keystore AES-GCM, never
logged or in the repo; NO face identification (hard rule) — no
person features in this scope at all; imperial/plain-language UI
copy; fleet signing key and the GitHub + version.json ship routine.

## Audits (change-impact, summary — full pass before build)

- Blast radius: this app only; config format GAINS the identity
  block (migration: v1 configs upgrade in place, identity learned
  on first verified contact; downgrade to 1.0.7 loses identity but
  keeps cameras).
- Riskiest seams: (a) identity mis-match — gated by end-to-end
  proof + ambiguity refusal; (b) restart storms on a dead road —
  global circuit breaker + caps; (c) boot-launch variance across
  Android builds — field-verified on the W2, HOME-category fallback;
  (d) deep buffer vs "live feel" — stated trade, settings-exposed;
  (e) supervisor rewrite touches the one component that currently
  works — v1.0.7 stays published/installable as the rollback until
  v2 is field-proven.
- Test plan: unit (identity matcher incl. presented-MAC alias and
  ambiguity refusal; supervisor state machine incl. breaker;
  road-meter thresholds), harness (house-4 real SPS regression
  carries over), device (W2 dress rehearsal: cold boot → grid,
  power-cycle address shuffle → tiles heal by identity, road-wedge
  simulation → banner + backoff, D-pad-only full pass).

## Phases

- P1 — engine: identity store + resolver, supervisor, road meter,
  diagnostics v2 (touch UI carried over).
- P2 — stage UI (HOUSE/DVR/ALL), D-pad focus pass, boot-to-cameras.
- P3 — W2 field proof (dress-rehearsed before any witnessed test;
  ready-before-call-out rule applies).
- P4 — hardening from field results; fleet rollout call by HQ.

## DVR — supplied facts and the design consequence (2026-11-02, via Main/Mechanic)

There is NO static stream URL for the DVR, and there never will be
one on the app side: the van's JOINLGO DVR (CMSV7 device 104996)
addresses streams through the vendor's cloud gateway with URLs
embedding a CMSV7 session id that expires within hours and is
re-minted laptop-side (ThinkPad Waydroid). The app does NOT mint
its own session — account credentials on-device are NOT approved.

Design: the four DVR tiles are RELAY-fed, house-4-style. The laptop
publishes the DVR feed on its LAN camera relay (the mediamtx that
already serves house-4's transcode), paths /dvr-1 … /dvr-4
(configurable host/paths in Settings, defaults to be confirmed with
the Laptop lane when the leg is revived). Tiles label their source
"Relay" (LiveState already carries the label). The app probes the
feed honestly: until the laptop leg is revived (DOWN since
2026-10-23; a separate job, not this lane's, and it does not jump
the God's Eye queue), every DVR tile renders the plain state "DVR
feed is down — the van DVR's link at home is off right now," and
the supervisor keeps a slow-lane probe so the row lights up by
itself when the feed returns. No fake tiles, ever. In the ALL view,
house tiles stay Direct and DVR tiles say Relay — per-tile honesty.
Away mode: DVR is out of scope (the Away relay carries house-1..6)
unless HQ extends it.

## Fleet priority (owner ruling 2026-11-02, via Main)

God's Eye keeps TOP priority across the fleet. Camera v2 proceeds
in parallel as a separate lane; if HQ attention must choose, God's
Eye wins and camera phase gates may slide. The pre-Dad target
(field-proven on the W2 before Nov 16) is the aim, not a suicide
pact.

## Owner rulings (2026-11-02, via Main)

1. DVR details: requested from the Mechanic lane; arrive via Main.
2. BOOT: APPROVED as recommended — BOOT_COMPLETED receiver;
   home-app only as fallback if a box refuses (W2 check in P3).
3. BUFFER: AMENDED — the steadiness trade is a user-facing Settings
   toggle ("Steady picture"), default ON (grid ~10 s, single ~4 s);
   OFF = lowest latency. Changing it re-opens streams; no rebuild
   or app restart. Implemented in P1 (engine reads it at stream
   start; MainActivity rebuilds players on resume).
