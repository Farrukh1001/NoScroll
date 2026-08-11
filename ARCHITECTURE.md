# Architecture

NoScroll is a single-module Android application. This document explains how it is put together and,
more usefully, *why* several of the load-bearing decisions went the way they did.

## The shape of it

```
com.noscroll
├── core/time          TimeSource — the seam that makes every timer testable
├── domain/            Pure Kotlin. No Android imports. This is where the rules live.
│   ├── protection/    Protected settings, change classification, the waiting period
│   ├── instagram/     Shared budget, emergency extension
│   ├── detect/        Surface detection over a plain UiNode tree
│   ├── filter/        Domain matching, rule updates, DNS/IP packets, browser guard
│   ├── wellbeing/     Usage aggregation, honest time-saved comparisons
│   ├── notify/        Notification quota, message catalogue
│   ├── health/        Whether protection is actually working
│   └── surface/       The Surface enum and package list
├── data/              DataStore and SQLite persistence
├── service/           Accessibility service, VPN service, receivers, notifier
├── work/              WorkManager scheduling
├── di/                One hand-written container
└── ui/                Compose screens
```

The `domain` package has no Android dependencies at all. Everything with a rule in it — what
counts as weakening a setting, when the Instagram clock runs, whether a domain matches, how a DNS
packet is parsed — lives there and is covered by plain JVM unit tests. The Android layers are thin
adapters over it.

That split is what makes the test suite meaningful. "The waiting period survives a clock rollback
across a reboot" is a three-line test against `ProtectedSettingsEngine`, not an instrumentation test
that needs a device and a way to fake the system clock.

## Decisions worth explaining

### Two clocks, not one

`TimeSource` exposes both wall-clock and monotonic time, and the difference matters.

Durations are accumulated on the monotonic clock, so moving the system clock forward cannot mint
Instagram minutes or expire a waiting period early. But the monotonic clock resets on reboot, and a
waiting period has to keep running while the phone is off — so a pending change re-anchors against
the wall clock after a reboot and credits only forward movement. Rolling the clock backwards
therefore buys nothing, and, importantly, also costs nothing: the user can never be locked out by a
clock change.

### Detection is data, not behaviour

Detectors take a `UiNode` — a plain data snapshot — rather than an `AccessibilityNodeInfo`. Two
reasons. Tests can build the exact tree for an awkward case (a Reel opened from a DM, a Shorts shelf
that is not the Shorts player). And it draws a hard line around screen content: a snapshot exists for
the length of one detection pass and is then dropped. Nothing from it is persisted, logged, or sent
anywhere.

The one detail that catches everyone: **inside Instagram, `clips_*` means Reels and `reel_*` means
Stories.** Getting those backwards would count Stories against the budget and let Reels run free. The
Stories check runs first and wins.

### The filter routes one address, not all traffic

The Explicit Content Shield is a `VpnService`, but the tunnel does not carry the device's traffic. It
advertises a DNS server at a private address and routes only that address into the TUN device.
Everything else uses the normal network path untouched.

A full userspace TCP/IP stack would mean re-implementing NAT and TCP in this process, adding a large
third-party networking dependency, and putting every byte the user sends through code that has to be
perfect or their phone stops working. Routing a single address gets the same content-blocking result
with a fraction of the blast radius. Blocked names get NXDOMAIN — no answer rewriting, no TLS
interception, no CA certificate, no logging.

The limitations of filtering at the DNS layer are real and documented in `THREAT_MODEL.md`. The
accessibility browser guard exists as the second layer precisely because of them.

### No annotation processors

No Room, no Hilt, no KSP. Persistence is DataStore for configuration and hand-written SQL over the
AndroidX SQLite support library for wellbeing aggregates; dependency injection is one `AppContainer`
with lazy properties.

For an app whose release artefact people are expected to reproduce from source, every code generator
in the build is a step someone has to trust. The wellbeing schema is three flat tables with no
relations, and the app has about eight singletons. Neither justifies the machinery.

### Separate stores, deliberately

Protection config, pending changes, the Instagram budget, notification quota, filter rules and
wellbeing aggregates are six separate stores. This is a correctness requirement, not tidiness:
"delete my screen-time history" must be instant and complete, and it must not take the Instagram
budget or a pending protected change with it. Different files make that impossible to get wrong.

### Overlays without draw-over-other-apps

Every overlay is a `TYPE_ACCESSIBILITY_OVERLAY` window, which an accessibility service may use
without `SYSTEM_ALERT_WINDOW`. NoScroll never requests that permission, so it cannot draw over
anything outside the apps it protects.

`OverlayController` keys every window by id and treats its map as the single source of truth, which
is what prevents the classic failure of this kind of app: a stale blocker floating over the launcher,
or three copies of a mask stacked after a rotation.

### The accessibility service sees almost nothing

`android:packageNames` in the service config limits event delivery to YouTube, YouTube Music,
Instagram and supported browsers. Events from a banking app or a password manager never reach this
process at all — a stronger guarantee than filtering after the fact.

Detection works off view resource ids rather than the words on screen, which is both more reliable
across languages and far less sensitive.

## Data flow

**Enforcement.** Accessibility event → `NodeSnapshot` → detector → guard → `ProtectionRepository`
(for the live setting) → `OverlayController`.

**Budget.** Guard detects a budgeted surface → monotonic slice accumulates → flushed to
`InstagramBudgetRepository` every few seconds and at every exit path → blocker at zero.

**Insights.** `MaintenanceWorker` reads `UsageStatsManager` events → `UsageAggregator` →
`WellbeingDatabase` → `TimeSavedCalculator` → `MessageCatalogue` → UI.

**Filter.** TUN read → `IpPacket.parseUdp` → `DnsMessage.parseQuery` → `RuleSet.isBlocked` → either
`buildNxDomainResponse` or forward to the network's own resolver.

## Testing

See `TESTING.md`. The short version: the rules are pure functions, so they are tested as pure
functions.
