# NoScroll

An Android app that removes infinite-scroll surfaces from the apps you keep, without removing the
apps.

It masks YouTube Shorts, blocks YouTube Music, puts one shared daily budget on Instagram's discovery
surfaces while leaving messages and Stories completely alone, and filters sexually explicit sites at
the DNS layer. Changes that weaken protection wait — 24 hours by default — before they take effect.

No account. No ads. No servers. No telemetry. Nothing it sees leaves your phone.

**GPL-3.0-only.**

---

## What it actually does

### YouTube

- Shorts shelves in the Home feed are covered with a small "Shorts hidden." mask.
- The Shorts button in the bottom navigation bar is covered when it can be located confidently.
- If the Shorts viewer opens anyway, NoScroll presses Back once and shows a blocker with a way out.
- **Normal videos are never affected.** Detection only acts on a positive Shorts match; an
  unrecognised screen is treated as ordinary YouTube and left alone.
- A Shorts link a friend sends opens as a single ordinary video (`/watch?v=…`) instead of dropping
  you into the feed.

### YouTube Music

Blocked outright, with no override, no countdown, and no "open anyway".

Stated honestly: **an ordinary Android app cannot disable another app.** NoScroll can block YouTube
Music while it is on screen, and it will offer to take you to its App Info page — but *disabling or
uninstalling the package is genuinely stronger*, because it also stops background and Bluetooth
playback, which a foreground blocker cannot. The dashboard reflects the difference: "disabled" or
"not installed" reads as fully protected; "installed and enabled" reads as fallback-protection mode.

### Instagram

One shared daily budget — 30 minutes by default — across **Reels, the Reels tab, Explore, search
discovery, Saved, Collections, and anything opened from them**.

The budget is **per device, per day, and shared across every account.** It is never keyed by
username, profile or login session, and NoScroll never reads or stores who is signed in. Switching
accounts continues the same budget; an override spent on one account is spent everywhere.

Never counted, never blocked:

- Reading and writing DMs, and scrolling inside a conversation
- Stories
- Posting and creation flows
- Notifications, settings, editing your profile
- Ordinary photos and carousels opened directly from a profile

A Reel a friend sends in a DM plays as **one Reel**. Swiping to a second one returns you to the
conversation. Playback, sound and Back all keep working normally — NoScroll does not intercept swipe
gestures, because doing so requires putting the whole device into touch-exploration mode.

When the budget runs out you get a blocker on discovery surfaces only, with DMs and Stories still
available, and — once per day, if enabled — the option to request extra time.

**Emergency extra time** requires choosing a whole number of minutes from 1 to 30, waiting out a
60-second countdown that pauses whenever the app is not in front of you, and typing
`I choose X extra minutes intentionally today.` by hand. Once per local day, Instagram only.

### Explicit Content Shield

Blocks sexually explicit material at the DNS layer through a local `VpnService`, plus a browser-level
guard for explicit searches.

**Deliberately not targeted:** gore, violence, killing, horror, crime, ordinary manga and manhwa,
mature fiction without explicit sexual content, medical information, sexual-health education, and
abuse-prevention resources. Searches with health, education, support, news or academic context are
explicitly exempted, because a blocked helpline search is a real harm and a missed block is not.

There is no emergency bypass for explicit content.

---

## Screenshots

Screenshots are added after the first device build. This section will hold: the Today dashboard, the
Protect tab, a pending-change bundle, the Instagram exhaustion blocker, and the emergency-extension
flow.

*(Placeholder — no images are committed yet.)*

---

## Protected settings and the waiting period

Anything that **strengthens** protection applies immediately: turning a protection on, lowering the
Instagram limit, disabling the emergency extension, adding a blocked domain, enabling SafeSearch,
enabling fail-closed mode, lengthening the waiting period.

Anything that **weakens** protection is queued behind the waiting period, and you see exactly what
will change and when:

- Turning a protection off
- Raising the Instagram limit
- Enabling or increasing emergency time
- Removing a blocked domain or adding an allowlist entry
- Turning off SafeSearch, fail-closed mode, or the YouTube Music lockdown

One pending change at a time. You can cancel it instantly; you cannot speed it up or edit it.

**Shortening the waiting period waits for the old one.** Going from 48 hours to 1 hour takes 48
hours. The waiting period itself can only be changed once per rolling 24 hours, and every change to
it has at least a one-hour pending period.

Available delays: 1, 2, 4, 8, 12, 24 (default) and 48 hours.

Privacy choices are **never** delayed. Revoking Usage Access, turning off analytics, deleting your
history, disabling notifications and exporting or deleting your data all happen immediately.

---

## Uninstalling — stated plainly

**NoScroll does not resist uninstallation, and this is deliberate.**

It does not block Android Settings, does not cover the uninstall dialog, does not impersonate device
management, does not use accessibility to prevent removal, and does not use device-owner
provisioning. An ordinary self-installed APK can always be removed by whoever owns the phone, and any
app claiming otherwise is either lying or doing something you should not want on your device.

The waiting periods apply to changes made *inside* the app. They do not apply to Android's Uninstall
button. If you want stronger commitment than a self-imposed delay, that has to come from somewhere
other than software you control.

---

## Installation

### From a release APK

1. Download `NoScroll-v1.0.0.apk` and `NoScroll-v1.0.0.apk.sha256` from the Releases page.
2. Verify the checksum:
   ```
   sha256sum -c NoScroll-v1.0.0.apk.sha256      # Linux/macOS
   certutil -hashfile NoScroll-v1.0.0.apk SHA256 # Windows
   ```
3. Install it. Android will warn you about installing from an unknown source; that warning is
   correct and you should only proceed if you verified the checksum.

### "Allow restricted settings"

Android restricts accessibility access for sideloaded apps. If the NoScroll toggle in Accessibility
settings is greyed out:

1. Settings → Apps → NoScroll
2. Tap the three-dot menu in the corner
3. Choose **Allow restricted settings**
4. Return to Accessibility settings and enable NoScroll

### Permissions, and why each one exists

| Permission | Why | Optional? |
|---|---|---|
| **Accessibility service** | To tell which screen of YouTube, YouTube Music or Instagram is open. Restricted by manifest to those apps and supported browsers — it never receives events from anything else. Reads which screen you are on, never what you write. | Required |
| **VPN** | Runs the local DNS filter. No traffic is uploaded, no history is kept, no certificate is installed. | Required for the shield |
| **Usage Access** | Screen-time insights only. Every protection works without it. | Optional |
| **Notifications** | The evening summary and protection-health alerts. Can be set to zero. | Optional |
| **Foreground service** | Android requires it for the VPN, and shows a quiet permanent status row. | Required for the shield |

NoScroll does **not** request `QUERY_ALL_PACKAGES` (it names the three packages it protects),
`SYSTEM_ALERT_WINDOW` (accessibility overlays need no such permission), `SCHEDULE_EXACT_ALARM` or
`USE_EXACT_ALARM` (there is no alarm clock here).

### Always-on VPN

For stronger protection, turn on always-on VPN with **Block connections without VPN** in Android's
VPN settings. Without it, the filter can be switched off from the notification shade. NoScroll also
supports failing closed: if the filter is meant to be running and is not, supported browsers are
blocked until it comes back rather than being quietly left unprotected.

---

## Notifications

At most **three** discrete notifications per local day. **Two** by default. Configurable to 0, 1, 2
or 3.

The evening summary defaults to **10:00 PM** and reserves one slot when enabled. There is no alarm
clock and no exact-alarm permission — delivery is best-effort *around* your chosen time, and the app
says so rather than pretending otherwise.

The permanent status row while the filter runs is a silent notification Android requires. It is not a
message and does not count against the quota.

---

## Privacy

- No backend, no accounts, no ads, no telemetry, no remote logging.
- No browsing history — not the domains that were allowed, and not the ones that were blocked.
- No raw accessibility content, no messages, no usernames, no collection names, no video titles, no
  search queries.
- Screen-time data is aggregate only, stays on the device, and can be deleted at any time.
- Deleting your screen-time history does not reset the Instagram budget or a pending protected
  change — different stores, on purpose.

Full detail in [PRIVACY.md](PRIVACY.md) and [THREAT_MODEL.md](THREAT_MODEL.md).

---

## Known limitations

These are real and not worked around. They are here because an app in this category that hides its
gaps is worse than useless.

**Third-party UI changes.** Detection depends on YouTube's and Instagram's view hierarchies. When
they redesign, detection can degrade. NoScroll fails toward *not* blocking, so a redesign means
Shorts might slip through — not that your DMs get covered.

**DNS is not the whole internet.** A browser or app using DNS-over-HTTPS or DNS-over-TLS bypasses
the filter, as does one connecting to a hardcoded IP. Encrypted Client Hello hides hostnames from any
network-level filter. The browser guard and fail-closed mode exist because of this.

**Mixed-content platforms.** A site that serves both ordinary and explicit content from one domain
cannot be filtered by name without blocking all of it.

**YouTube Music background playback.** Foreground blocking cannot reliably stop audio that is already
playing in the background or over Bluetooth. Disable or uninstall the package for real protection.

**Gesture interception.** Not used. Preventing a swipe requires touch-exploration mode, which changes
how every app on the phone responds to touch. Single-item mode watches the pager's reported position
instead.

**OEM battery management.** Some manufacturers kill background services aggressively. If protection
keeps stopping, exempt NoScroll from battery optimisation.

**Notification timing.** WorkManager is best-effort. Android may shift the evening summary.

---

## Building from source

Requirements: JDK 17+, Android SDK with platform 36.

```bash
git clone https://github.com/Farrukh1001/NoScroll.git
cd NoScroll
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

./gradlew test          # unit tests
./gradlew lint          # static analysis
./gradlew assembleDebug # debug APK
```

For a signed release build and the `dist/` artefacts, see [RELEASE.md](RELEASE.md).

### Signing-key continuity

Android identifies an app by its signing key. **If you lose the release key, you cannot ship an
update that installs over an existing copy** — users would have to uninstall (losing their data) and
reinstall. Key material lives under `local-signing/`, which is git-ignored in its entirety. Back it
up somewhere safe and offline.

---

## Reporting a false positive

If NoScroll blocks something it should not — particularly anything medical, educational, or
support-related — please open an issue.

**You do not need to include the domain or the search.** Say what category it was ("a sexual-health
information site", "an academic paper about content moderation") and roughly what you were doing. The
app deliberately keeps no record of what it blocked, so there is nothing to attach even if you wanted
to. Category and context are enough to fix the rule.

---

## Documentation

| | |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | How it is built and why |
| [PRIVACY.md](PRIVACY.md) | What is collected (very little) and what is not |
| [SECURITY.md](SECURITY.md) | Reporting vulnerabilities |
| [THREAT_MODEL.md](THREAT_MODEL.md) | What this does and does not defend against |
| [TESTING.md](TESTING.md) | Test strategy and how to run everything |
| [RELEASE.md](RELEASE.md) | Signing and releasing |
| [TROUBLESHOOTING.md](TROUBLESHOOTING.md) | When something is not working |
| [CONTRIBUTING.md](CONTRIBUTING.md) | How to contribute |
| [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) | Dependency licences |
| [docs/LIMITATIONS.md](docs/LIMITATIONS.md) | Every known gap, in one place |
| [docs/ACCEPTANCE.md](docs/ACCEPTANCE.md) | Each requirement, where it lives, how it is verified |
| [docs/DEVICE_TESTING.md](docs/DEVICE_TESTING.md) | The checklist that needs a real phone |

---

## Licence

GPL-3.0-only. See [LICENSE](LICENSE).
