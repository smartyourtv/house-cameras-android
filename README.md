# House Cameras (Android)

Watch your house Wi-Fi cameras on your Android phone — at home straight
from the cameras, and away from home through the small relay that runs
at home (on the ThinkPad, over Tailscale).

Built to accept as wide a range of camera families as possible: one core
with per-family templates (HiP2P/CamHi, Reolink, Dahua/Amcrest,
Hikvision, Foscam, XMEye, Tapo, generic). Adding a family means adding a
template — never a rewrite. Discovery runs ONVIF WS-Discovery, HiP2P
VSCP, Reolink identification, and a plain LAN sweep together. Each
camera is tried in order — ONVIF, family template, known RTSP paths,
MJPEG, photo polling — and the first way that produces a real picture
is kept. H.264, HEVC and MJPEG all play.

**Honest limit:** cameras that only work through a company cloud app
(Ring, Blink, stock Wyze, Nest) send no picture on your home network,
so they cannot be added. The app says so plainly if a camera offers
no local picture.

## Privacy & safety
- Camera passwords are entered on the phone, locked with the phone's
  secure key store (Android Keystore), never logged, never sent
  anywhere except to the camera itself.
- Away mode never talks to a camera directly — only to the home relay,
  with the session code the relay gives you.
- Crash reports stay on the phone (in the app and in Downloads) unless
  you choose to share one. Nothing is uploaded by itself.

## Install
Download `HouseCameras-latest.apk` from the latest GitHub Release and
open it on the phone; Android will ask you to allow installs from your
browser/files app the first time. Updates are checked inside the app —
you always tap to install, and every download is checksum-verified.

Design notes live in `docs/DESIGN.md`; audits in `docs/AUDITS.md`.
This app is the Android sibling of House Cameras for Windows — same
camera families, same order of trying, same seeded fleet.
