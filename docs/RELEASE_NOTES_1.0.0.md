# NoScroll 1.0.0

The first release.

NoScroll removes infinite-scroll surfaces from the apps you keep, without removing the apps. It masks
YouTube Shorts, blocks YouTube Music, puts one shared daily budget on Instagram's discovery surfaces
while leaving messages and Stories completely alone, and filters sexually explicit sites at the DNS
layer. Changes that weaken protection wait — 24 hours by default — before taking effect.

No account. No ads. No servers. No telemetry.

## Install

```
sha256sum -c NoScroll-v1.0.0.apk.sha256
```

Verify the checksum before installing. Android will warn about installing from an unknown source;
that warning is correct.

Sideloaded apps need one extra step for accessibility access: **Settings → Apps → NoScroll →
three-dot menu → Allow restricted settings.** Without it the accessibility toggle stays greyed out.

## What it does

**YouTube** — Shorts shelves and the Shorts navigation button are masked. If the viewer opens anyway,
it steps back once and shows a blocker. Normal videos are never affected. A Shorts link someone sends
opens as one ordinary video instead of a feed.

**YouTube Music** — Blocked outright, no override. The app is honest that disabling or uninstalling
the package is genuinely stronger, because a foreground blocker cannot stop background or Bluetooth
playback.

**Instagram** — One 30-minute daily budget across Reels, Explore, search discovery, Saved and
Collections. Shared across every account on the device, never keyed by username. DMs and Stories are
never counted and never blocked. A Reel from a DM plays as one Reel. Extra time, once a day, needs a
60-second countdown and a hand-typed sentence.

**Explicit Content Shield** — DNS-level filtering through a local VPN that routes only its own
resolver address, so ordinary traffic never enters the tunnel. Blocked lookups get NXDOMAIN; nothing
is logged, rewritten or uploaded. Gore, violence, crime, horror and ordinary manga/manhwa are
deliberately not targeted, and health, education and support searches are explicitly exempted.

## Things worth knowing before you install

**It can be uninstalled, and that is deliberate.** No anti-uninstall behaviour, no device admin, no
blocked Settings. The waiting periods apply to changes made inside the app, not to Android's Uninstall
button.

**Encrypted DNS bypasses the filter.** Many browsers enable DNS-over-HTTPS by default. Turning it off
is in TROUBLESHOOTING.md, and fail-closed mode blocks supported browsers when the filter is down.

**Detection depends on YouTube's and Instagram's current UI.** When they redesign, detection can
degrade. It always fails toward *not* blocking, so a redesign means Shorts might slip through — not
that your DMs get covered.

**The evening summary is best-effort.** NoScroll uses WorkManager and requests no alarm permissions,
so Android may shift the 10:00 PM reminder by a few minutes.

The full list is in `docs/LIMITATIONS.md`.

## Privacy

No browsing history, no search terms, no raw accessibility content, no usernames, no titles. The
accessibility service is restricted by manifest to the four apps it protects, so events from
everything else on your phone never reach it. Screen-time insights are optional, local, and deletable
at any time — and deleting them does not reset your Instagram budget or a pending change.

## Verifying this build

```
apksigner verify --print-certs NoScroll-v1.0.0.apk
sha256sum -c NoScroll-v1.0.0.apk.sha256
```

Built from source with `./gradlew packageDist`. 230 unit tests, lint clean.

## Reporting problems

Filter false positives — especially anything medical, educational or support-related — are treated as
serious bugs. **You do not need to include the domain or the search:** the app keeps no record of
either. The category and rough context are enough.

Licensed under GPL-3.0-only.
