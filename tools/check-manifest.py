#!/usr/bin/env python3
"""Manifest hygiene gate (Pack H, 2026-11-22).

Checks the SOURCE manifest the way the next audit would:
- no permission is declared twice;
- every declared permission has a use site in the app sources, or
  is the one named exemption (SYSTEM_ALERT_WINDOW — held under the
  HQ ruling of 2026-11-02 as a boot-launch exemption, disclosed in
  the manifest comment and in Settings; the app never draws
  overlays).

Exit 0 = clean. Run from anywhere; the repo root is this script's
grandparent. Joins the ship gate beside the packaging validator.
"""
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
MANIFEST = os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml")
SRC = os.path.join(ROOT, "app", "src", "main", "java")

# Permission -> a source token proving a use site.
USE_SITES = {
    "android.permission.INTERNET": "URL(",
    "android.permission.ACCESS_WIFI_STATE": "WifiManager",
    "android.permission.CHANGE_WIFI_MULTICAST_STATE": "MulticastLock",
    "android.permission.REQUEST_INSTALL_PACKAGES": "ACTION_VIEW",
    "android.permission.RECEIVE_BOOT_COMPLETED": "BOOT_COMPLETED",
}
EXEMPT = {"android.permission.SYSTEM_ALERT_WINDOW"}


def main():
    text = open(MANIFEST, encoding="utf-8").read()
    declared = re.findall(r'uses-permission[^>]*android:name="([^"]+)"', text)
    problems = []
    seen = set()
    for perm in declared:
        if perm in seen:
            problems.append("declared twice: %s" % perm)
        seen.add(perm)
    corpus = ""
    for dirpath, _dirs, files in os.walk(SRC):
        for name in files:
            if name.endswith(".kt"):
                corpus += open(os.path.join(dirpath, name),
                               encoding="utf-8").read()
    for perm in declared:
        if perm in EXEMPT:
            continue
        token = USE_SITES.get(perm)
        if token is None:
            problems.append("no use-site rule for %s — add one or "
                            "remove the permission" % perm)
        elif token not in corpus:
            problems.append("declared but unused: %s" % perm)
    if problems:
        for p in problems:
            print("FAIL  %s" % p)
        return 1
    print("Manifest clean: %d declarations, %d unique, every one "
          "used or the named exemption." % (len(declared), len(seen)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
