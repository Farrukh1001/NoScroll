# Changelog

All notable changes to this project are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] — 2026-08-11

First release.

### YouTube

- Shorts shelves in the Home feed masked when confidently detected
- Shorts navigation button covered when it can be located
- Shorts viewer exited with a single rate-limited Back press, then a stable blocker with a way out
- Shared Shorts links opened as one ordinary video, with strict URL validation
- Normal videos deliberately untouched; unrecognised screens are never blocked

### YouTube Music

- Blocked when in the foreground, with no override of any kind
- Package state (not installed / disabled / enabled) surfaced honestly on the dashboard
- Route to the system App Info page for disabling or uninstalling, with the trade-off explained
- No force-stopping, no automated taps inside Settings

### Instagram

- One 30-minute daily budget shared across Reels, the Reels tab, Explore, search discovery, Saved,
  Collections, and content opened from them
- Shared across every account on the device; never keyed by username, profile or session
- DMs, Stories, posting, notifications, settings and profile browsing never counted or blocked
- Friend-sent Reels play one at a time, detected from the pager's own reported position rather than
  by intercepting gestures
- Reel cards masked in the Home feed; ordinary photos and carousels left alone
- Remaining-time banner while browsing Collections
- Emergency extension: 1–30 minutes, 60-second foreground-only countdown, hand-typed confirmation
  sentence, once per local day
- Budget measured on the monotonic clock and flushed frequently, so it survives process death,
  reboot, timezone changes and app updates without granting fresh time

### Explicit Content Shield

- Working DNS filter over `VpnService`, routing only the tunnel's own resolver address so ordinary
  traffic is never carried
- Blocked lookups answered with NXDOMAIN; no answer rewriting, no TLS interception, no CA
  certificate, no logging
- Bundled offline blocklist covering adult sexual content only
- Signed rule-update framework (ECDSA P-256) with last-known-good fallback, downgrade rejection and
  expiry checking
- SafeSearch enforced by re-issuing searches with the engine's own parameter
- Browser guard for explicit searches, with health, education, support, news and academic context
  explicitly exempted
- Gore, violence, crime, horror and ordinary manga/manhwa deliberately not targeted
- Fail-closed mode blocks supported browsers when the filter is down
- No emergency bypass

### Protected settings

- Every module and sub-feature independently configurable
- Strengthening changes apply immediately; weakening changes queue behind the waiting period
- Delays of 1, 2, 4, 8, 12, 24 (default) or 48 hours
- One pending bundle at a time, cancellable but never accelerable, with a cooldown after cancelling
- Shortening the delay waits for the old delay; the delay itself changes at most once per 24 hours
- Waits anchored to the monotonic clock, re-anchored across reboots, resistant to clock manipulation
  without any risk of locking the user out
- Recommended, Strict and Communication-first presets

### Wellbeing

- Optional local screen-time insights via Usage Access; every protection works without it
- Event aggregation handling missing pairs, midnight splits, DST, reboots and multiple activities
- "Time saved" only from like-for-like windows, with the baseline named; no first-day saving, no
  partial-versus-full-day comparison, no negative saving, and no minutes ever estimated from blocked
  attempts
- 12-month default retention, configurable, deletable at any time

### App

- Today, Insights, Protect, Calendar and Settings, in Material 3 with light and dark themes
- Charts drawn on Compose Canvas, each with a text summary
- Ten-step onboarding that refuses to mark setup complete while protection is unhealthy
- At most three notifications a day, two by default, configurable to zero
- Evening summary at 10:00 PM by default, scheduled with WorkManager — no alarm clock and no
  exact-alarm permission
- Sanitised diagnostics containing no user content
- No anti-uninstall behaviour, stated plainly in the app and the README

### Privacy

- No backend, accounts, ads, telemetry or remote logging
- No browsing history, search queries, raw accessibility content, usernames or titles stored
- Accessibility service restricted by manifest to the packages it protects
- No `QUERY_ALL_PACKAGES`, `SYSTEM_ALERT_WINDOW`, or exact-alarm permissions
- Cloud backup and device transfer disabled so protection state cannot be rolled back by a restore
- Privacy controls exempt from the waiting period

[1.0.0]: https://github.com/OWNER/NoScroll/releases/tag/v1.0.0
