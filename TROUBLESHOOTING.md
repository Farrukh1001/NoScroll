# Troubleshooting

## The Accessibility toggle is greyed out

Android restricts accessibility access for sideloaded apps. Settings → Apps → NoScroll → three-dot
menu → **Allow restricted settings**, then return to Accessibility settings.

## Protection keeps stopping

Almost always OEM battery management. Samsung, Xiaomi, Oppo, Vivo, OnePlus, Huawei and Realme are all
aggressive about killing background services.

1. Settings → Apps → NoScroll → Battery → **Unrestricted**
2. On Xiaomi/Redmi: Settings → Apps → NoScroll → **Autostart** on
3. On Samsung: Settings → Battery → Background usage limits → remove NoScroll from "Sleeping apps"
4. Lock NoScroll in the recent-apps view if your launcher supports it

The Protect tab shows whether the accessibility service is currently connected. Trust that over
whether the setting looks enabled.

## Shorts are getting through

Detection works from YouTube's view hierarchy, and YouTube changes it. NoScroll deliberately fails
toward *not* blocking — the alternative is masking parts of the app that are fine.

If Shorts started slipping through after a YouTube update, please open an issue with your YouTube
version (Settings → About in YouTube). No account details or screenshots of your feed are needed.

## Something legitimate is being blocked

If the shield blocks a medical, sexual-health, educational, academic, news or support resource, that
is a bug and is treated as a serious one.

Open an issue describing the **category** and roughly what you were doing — "a sexual-health
information site", "a paper about content moderation". You do not need to include the domain or the
search, and the app keeps no record of either, so there is nothing to attach even if you wanted to.

As an immediate workaround you can add the domain to your local allowlist in the app. Note that this
weakens protection, so it goes through the waiting period.

## The filter is not running

Check the Protect tab. Common causes:

- **Another VPN is active.** Android permits one at a time. NoScroll cannot run alongside it.
- **Consent was revoked.** Re-run setup from the Protect tab.
- **The service was killed.** See battery management above.

With fail-closed mode on, supported browsers are blocked while the filter is down. That is intended:
the alternative is browsing that looks protected and is not.

## Explicit sites still load

Most likely your browser uses DNS-over-HTTPS, which bypasses DNS filtering entirely.

- **Chrome:** Settings → Privacy and security → Use secure DNS → off
- **Firefox:** Settings → DNS over HTTPS → off
- **Brave:** Settings → Privacy → Secure DNS → off

Some sites also connect to hardcoded IPs, and Encrypted Client Hello hides hostnames from any
network-level filter. These are genuine limitations of filtering by name, documented in
[THREAT_MODEL.md](THREAT_MODEL.md). The browser guard exists as the second layer for exactly this
reason.

## YouTube Music still plays

Foreground blocking cannot reliably stop audio that is already playing, especially over Bluetooth.

Disable or uninstall the package — Settings → Apps → YouTube Music → Disable. The Protect tab has a
shortcut. This is genuinely stronger than what any ordinary app can do, which is why NoScroll asks
rather than pretending its blocker is equivalent.

## The Instagram budget seems wrong

**It is shared across every account on the device.** That is by design: it is keyed by device and
date, never by username. Time spent on one account comes out of the same budget as another.

Time counts only while Instagram is in the foreground, the screen is on, and you are on a budgeted
surface — Reels, Explore, search discovery, Saved or Collections. DMs and Stories never count. If the
budget looks like it moved when you were only reading messages, that is a detection bug worth
reporting.

## The evening summary did not arrive

NoScroll uses WorkManager, not alarms, and requests no exact-alarm permission. Delivery is best-effort
*around* your chosen time and Android may shift it, particularly in Doze. This is the honest trade for
not requesting a permission meant for alarm clocks.

Also check: notifications enabled in Settings, the daily limit not set to 0, and the summary itself
enabled.

## A pending change is not applying

Check the remaining time on the Today or Settings screen. It cannot be sped up — that is the point.

The wait is anchored to the monotonic clock, so changing the system clock will not move it forward. A
reboot re-anchors against the wall clock and credits the time the device was off.

## Deleting my data reset something

It should not. Screen-time history lives in a different store from the Instagram budget and pending
protected changes, specifically so that deleting one cannot affect the other. If you see otherwise,
that is a bug worth reporting.

## Starting over

Uninstalling removes everything. NoScroll does not resist uninstallation — no blocked settings, no
covered dialogs, no device-admin tricks. See the README for why that is deliberate.

## Reporting a bug

Settings → Diagnostics has a sanitised report: service states, permission states, the current surface
enum, rule-set version, and an error category. It contains no usernames, messages, titles, searches,
domains or raw accessibility text. It is safe to paste into an issue.
