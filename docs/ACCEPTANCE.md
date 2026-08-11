# Acceptance criteria

Each criterion, where it is implemented, and how it is verified. "Device" means it needs a physical
phone with the real third-party apps installed — no amount of unit testing substitutes for that.

| # | Criterion | Where | Verified by |
|---|---|---|---|
| 1 | Normal YouTube videos work | `YouTubeDetector.isShortsViewer` only fires on a positive Shorts match | `DetectorTest.a normal watch page is not a shorts viewer` + device |
| 2 | Shorts entry surfaces masked when reliably detected | `YouTubeDetector.shortsShelves`, `shortsNavButton`; `YouTubeGuard` | `DetectorTest` shelf/nav tests + device |
| 3 | Shorts viewer exited or blocked | `YouTubeGuard.handleShortsViewer` — one Back, then a stable blocker | `DetectorTest` + device |
| 4 | External Shorts links open one isolated video | `ShortsLinkParser`, `ShortsLinkActivity` | `DetectorTest` link tests + device |
| 5 | YouTube Music disabled/uninstalled state verified | `AppContainer.youTubeMusicStatus`, `YouTubeMusicStatus` | `ProtectionHealthTest` + device |
| 6 | Enabled YouTube Music blocked immediately | `YouTubeMusicGuard.onForeground` | Device |
| 7 | YouTube Music has no override | No extension path exists for it — the emergency flow is Instagram-only | `EmergencyExtensionTest` (Instagram-scoped) + code review |
| 8 | One 30-minute shared cross-account budget | `InstagramBudgetState`, keyed by device + date only | `InstagramBudgetTest.the budget is shared across account switches` |
| 9 | Reels, Explore, Saved, Collections share it | `Surface.BUDGETED` | `SurfaceTest.every discovery surface is budgeted` |
| 10 | DMs and Stories unrestricted | `Surface.isInstagramProtectedFromBlocking`; Stories checked before Reels | `SurfaceTest`, `DetectorTest` stories/DM tests |
| 11 | Friend-sent Reels one at a time | `InstagramGuard.handleSingleItemMode` via pager position | `DetectorTest.the reel pager position…` + device |
| 12 | Emergency time accepts 1–30, rejects 31 | `EmergencyExtension.parseAmount` | `EmergencyExtensionTest` valid/invalid amount tests |
| 13 | 60-second foreground countdown and typed sentence | `CountdownState`, `EmergencyExtension.sentenceMatches` | `EmergencyExtensionTest` countdown/sentence tests |
| 14 | Explicit sexual domains filtered | `RuleSet`, `NoScrollVpnService`, bundled list | `FilterTest`, `DnsAndIpTest` + device |
| 15 | Manga/manhwa and gore/violence not blocked | `ExplicitQueryHeuristic` contains no such terms; bundled list excludes them | `FilterTest.gore, violence and ordinary comics are not targeted` |
| 16 | Incognito conventional traffic still covered | The filter is at the DNS layer, which is per-device not per-tab | Device |
| 17 | Filter-health failure visible; browsers fail closed | `ProtectionHealth`, `ShieldGuard.showFailClosed` | `ProtectionHealthTest` + device |
| 18 | Every module and sub-feature configurable | `SettingKey` (23 keys), Protect screen | `ProtectionCodecTest.every setting key has a stable id` |
| 19 | Weakening changes use the selected delay | `ProtectedSettingsEngine.request` | `ProtectedSettingsEngineTest.every delay preset is honoured` |
| 20 | Shortening the delay waits for the old delay | `ProtectedSettingsEngine.requestDelayChange` | `ProtectedSettingsEngineTest.shortening the delay waits for the old delay` |
| 21 | No anti-uninstall behaviour | Nothing implements it; no device-admin, no Settings blocking | Code review, `AndroidManifest.xml`, README/THREAT_MODEL |
| 22 | Screen-time analytics optional and local | `AppPreferencesRepository.analyticsEnabled`, `WellbeingDatabase` | `UsageAggregatorTest` + device |
| 23 | Five screens polished and functional | `ui/screens`, `ui/onboarding`, `ui/extension` | `ScreenTest`, `ThemeAndAccessibilityTest` (device) |
| 24 | "Time saved" uses comparable baselines | `TimeSavedCalculator` | `TimeSavedTest.a partial day is never compared against a full one` |
| 25 | Evening reminder defaults to 10:00 PM | `AppPreferencesRepository` default 22:00 | `SchedulingTest.the default reminder time is ten in the evening` |
| 26 | No alarm clock, no exact-alarm permission | WorkManager only; manifest declares neither | `SchedulingTest`, `AndroidManifest.xml` |
| 27 | Default quota 2, hard maximum 3 | `QuotaConfig.effectiveLimit` | `NotificationQuotaTest` limit tests |
| 28 | No raw browsing/accessibility/message/account data stored | `UiNode` is transient; no store has such a field | `PRIVACY.md`, code review, `ProtectionHealthTest.only an error category is recorded` |
| 29 | Process death, reboot, rotation, timezone, updates recover safely | `TimeSource` two-clock model, `BootReceiver`, idempotent rollover | `ProtectedSettingsEngineTest` reboot/clock tests, `InstagramBudgetTest`, `UsageAggregatorTest` DST test |
| 30 | Tests, lint and builds pass | — | `./gradlew test lint assembleDebug assembleRelease` |
| 31 | Signed APK and checksum in `dist/` | `packageDist` task | `dist/NoScroll-v1.0.0.apk` + `.sha256`, `apksigner verify` |
| 32 | GPL-3.0-only and GitHub-ready | `LICENSE`, `.github/` | File listing |

## Needs a physical device

These cannot be verified on a build machine, because they depend on the real YouTube and Instagram
apps and on Android's own permission flows:

- Shorts shelf and navigation-button masking against the current YouTube build
- Shorts viewer blocking and the Back-then-blocker escalation
- Instagram surface detection against the current Instagram build — Reels, Explore, Saved,
  Collections, and especially the DM/Stories exclusions
- Friend-sent Reel single-item mode
- YouTube Music foreground blocking
- The VPN tunnel establishing, DNS filtering end to end, and fail-closed browser blocking
- Accessibility service connection and the "Allow restricted settings" flow
- Notification delivery timing
- OEM battery-management survival

`docs/DEVICE_TESTING.md` has the checklist.
