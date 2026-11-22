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
  OK/center → fullscreen, Back → grid (plus the always-visible
  ALL CAMERAS control required by the 2026-11-06 third ruling,
  at the end of this document); settings, edit, diagnostics
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
unless HQ extends it. (This sentence is about DVR *viewing* in Away
mode. Recording is a separate subject, governed by the 2026-11-06
owner ruling at the end of this document.)

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

## Owner ruling (2026-11-06, via Main) — recording in scope: viewer + DVR, one total package

Owner ruling, 2026-11-06 (given in Main Chat 2): recording is IN
scope for this app, on Android and Windows alike. House Cameras is
viewer + DVR as ONE total package — the app is part of Scout, and
an install on any machine carries the whole package, not a
viewer-only subset. This reverses the v1 design line that put
recording out of scope (DESIGN.md's line is marked superseded;
the Windows DESIGN.md carries the same amendment).

- **Any install can record.** Which installs DO record is a
  per-install Settings choice (recorder on/off, default OFF).
  **Bill is this house's designated recorder (primary)** — Bill
  is the house's camera device. A ThinkPad/Scout install records
  if needed (secondary — while Bill is down, or for a specific
  need). The Android app carries the same capability and the same
  settings shape; no platform is a viewer-only edition.
- **One-recorder discipline.** By house rule only one install is
  the active recorder at a time, so the fleet never silently
  records the same cameras three times over. Recording state
  (on/off, space left, retention) joins the Status/diagnostics
  screen in plain words — the standing diagnostics rule.
- **Disk + retention are app settings** (a consequence named to
  the owner in the same exchange and accepted): storage location,
  retention by days and/or a size cap with oldest footage deleted
  first, and a per-camera choice of what records. A full disk is
  a stated Status-screen state, never a silent stop.
- **Android storage, stated honestly.** Phones and tablets have
  small, shared storage, and the system can stop background apps,
  so continuous round-the-clock recording is a mains-powered
  install's job (TV boxes, PCs). On Android the recorder is OFF
  by default; retention is capped by BOTH a day count and a
  storage budget (a size cap and/or a share of free space),
  oldest-first roll-off, recordings kept on the device (internal
  storage, or SD/USB where the device offers it) and never
  uploaded anywhere by the app. On a phone or tablet the honest
  promise is deliberate recording (while the app is open, or a
  set window) — the design says so rather than pretending a
  phone is an always-on DVR. TV boxes are mains-powered; for
  them the binding limit is storage size, handled by the same
  caps.
- **Alongside what already exists** — stated plainly, not
  silently duplicated:
  - The ThinkPad streamer already pulls the camera streams to
    serve live frames to Scout / God's Eye. That is a frame
    service, not an archive; app recording does not replace it,
    and it does not replace app recording.
  - Bill already runs the vendor client. Any recording that
    client does is the vendor's own silo; the app's recording
    is the Scout-family record, under this app's settings, laid
    out the same on both platforms.
  - The standalone DVR on the LAN keeps its own recordings on
    its own disk. The app neither manages nor copies it; this
    document's DVR row (viewing that unit's feed via the laptop
    relay) is a viewing feature, separate from app recording.
- Sequencing: this amendment is paper only. No build before
  Nov 15 (schedule-lock; the owner has not overridden it).

## Owner ruling (2026-11-06, via Main) — automatic discovery (second requirement of the same ruling)

Owner ruling, 2026-11-06 12:01 (via Main Chat 2): the app finds
its cameras BY ITSELF. (The failure behind the ruling is the
Windows build's Nov 6 stale-address failure on Bill; the
requirement is stated for both platforms, as one total package.)

- The app scans the LAN for known cameras **at startup** and
  **whenever a tile loses its camera**, and displays what it
  found on its own — no button press, no typed addresses.
  Relationship to this design as built: the §1 Resolver already
  resolves at startup and when a stream is unrecoverable; this
  ruling makes a full discovery scan the standing automatic
  behavior at both moments, not a manual feature (Find Cameras
  stays, as the user's own on-demand copy of the same scan).
- **Repointing only on identity proof** — a camera UID (ONVIF
  UUID / HiP2P UID), its MAC (including translated faces:
  behind a range extender a camera wears a translated MAC,
  and matching uses the surviving tail/alias forms), or a
  frame actually pulled from the candidate address. Never on
  address alone. This names the proof set explicitly inside
  the §1 match order: UID/MAC proof is the top grade (today's
  score 3), a pulled frame joins it as proof-grade evidence,
  and banner fingerprint stays what the P4.1 hotfix proved it
  is on this fleet — family evidence, never identity among
  siblings.
- **Unknown devices are never auto-trusted.** A device that
  matches no known camera appears as found-but-unclaimed, for
  the user to add deliberately; it never silently takes a
  known camera's slot or credentials.
- **Setup as easy as possible, no calibration.** A user never
  types an address for a camera the app can find and prove;
  manual entry stays as the fallback for what discovery
  cannot prove. When a moved camera cannot be proven, today's
  honest degrade stands: the tile says so plainly, the last
  address is kept, and scans/proofs/repoints show on the
  diagnostics screen (§5).
- Sequencing: paper only, with the recording ruling above.
  No build before Nov 15 (schedule-lock; not overridden).

## Owner ruling (2026-11-06, via Main) — view navigation: the way back is IN the app (third requirement)

Owner ruling, 2026-11-06 12:13 (via Main Chat 2), joining the
recording + automatic-discovery amendment. Zooming into one
camera works today, but there is no clear way back to the
all-cameras wall; the owner wants the way back IN THE
APPLICATION — "not from you explaining… something simple."

- **The way in stays:** tap (or OK on) a tile → single view.
- **The way back:** a plainly labeled **ALL CAMERAS** control,
  **always visible** in single view, **one tap** back to the
  wall. The label is the words ALL CAMERAS — plain language,
  no icon-only mystery, nothing to be taught.
- On TV the control is reachable by remote (D-pad focus) like
  every other control; on touch it is a thumb-sized labeled
  button. Today's Back → grid behavior (§4) stays as a second
  way back; the labeled control is the obvious one, the one a
  guest standing at the TV finds without asking.
- Same amendment terms: paper only, build after Nov 15.

## Owner note (2026-11-07, via Main Chat 2) — one shared camera login, plainly labeled (fourth requirement)

Owner note, 2026-11-07: the app should offer **one username +
password entry that covers ALL cameras** on the network — most
households run the same login on every camera. (His family-
installer reasoning: setup as easy as possible.)

State of the build, verified against the source 2026-11-07:
Android 2.0.0 does NOT have this. Camera credentials exist only
per camera, on each camera's own Edit screen — the owner sat on
house-1's settings card and did not find an apply-to-all option
because there isn't one. The Windows viewer has carried exactly
this since its v1.0.1 (2026-10-24: "Use this user name and
password for ALL cameras", per-camera override retained, added
after v1.0.0 made him type the shared password six times); the
Android build never got the port. Total-package parity says it
should have.

Requirement for the post-Nov 15 amendment build:
- A shared camera login: one username + password, entered once,
  covering every camera — plainly labeled in the obvious places:
  a "Camera login for all cameras" entry in Settings, and on each
  camera's Edit card the Windows-shaped offer ("Use this user
  name and password for ALL cameras") beside its per-camera
  fields. A guest or the owner must find it without being told.
- **Per-camera override kept:** a camera whose own login is set
  uses its own; the shared login is the default each camera
  falls back to. Changing the shared login updates every camera
  that relies on it and says so in plain words before saving.
- Storage unchanged: credentials stay on-device, Keystore-
  encrypted, never logged, never in the repo.
- Consequence, stated once: one shared password is one point of
  compromise for every camera — that is the household's own
  trade, the same one the Windows build already made, and the
  per-camera override is the escape hatch.
- Paper only, with the rest of the amendment set; build after
  Nov 15.

## Study input (2026-11-19) — Bill's vendor client, read-only study

From the Mechanic lane's read-only study of Bill's Windows
viewer (~/workspace/build-support/bill-viewer-study-20261118.md,
Part 1 gathered 2026-11-19; Part 2 = sourced field research):

- **Bill's client (HiP2P Client, Hichip) binds every camera by
  its factory P2P UID and stores no IP address for any camera,
  anywhere.** Sessions open by UID through the vendor's P2P
  stack, so address drift is a non-event in its design. The law
  transfers — identity that doesn't move, address as a
  per-session discovery result — and the machinery does NOT:
  his rendezvous is the vendor cloud (a dependency this design
  rejects); ours is LAN-local, by ONVIF UID / MAC (incl.
  translated faces) / pulled frame, per the 2026-11-06 ruling.
  Our cameras are the same HiP2P family and carry UIDs too;
  the identity store already holds a hip2pUid field — the
  honest limit stands that LAN protocols offer it only
  best-effort, so MAC/frame proof remains the working anchor.
- **His recording posture is thinner than the fleet assumed:**
  per-channel scheduled (one channel 24/7, four channels a few
  early hours two days a week, one channel nothing),
  overwrite-oldest. The recording amendment (§2026-11-06)
  mirrors the shape: per-camera schedules, not blanket 24/7,
  and oldest-first roll-off under the retention caps.
- **Cautionary tale:** his client's own event logs silently
  stop on Oct 26 while the client keeps running. Recording and
  logging health must be VISIBLE states on the diagnostics
  screen (this design's events log + per-camera rows answer
  it), never inferred from the process being alive — the same
  frame-freshness-over-liveness rule as the stream supervisor.
- Part 2 benchmarks carried into the supervisor pass: go2rtc's
  viewer hand-over (reconnect swaps viewers onto the new
  stream without dropping them; backoff 1→5→10→60 s) as the
  answer to visible tile flashing, Frigate's 20 s no-frame
  restart as a watchdog benchmark, per-camera RTSP keep-alive
  (SET_PARAMETER preferred, OPTIONS fallback, ~half the 60 s
  session timeout), and the correction that ffmpeg's
  `-reconnect*` flags are HTTP-only (RTSP wants socket
  timeouts + a supervising restart).
