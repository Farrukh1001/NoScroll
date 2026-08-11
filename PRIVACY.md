# Privacy

NoScroll has no backend, no accounts, no analytics service, and no way to send your data anywhere.
This document is specific about what that means, because "we respect your privacy" is worth nothing
without detail.

## What leaves your device

**One thing, and only when a signed rule-set update channel is configured:** an HTTPS request to
fetch an updated blocklist. That request contains no identifier, no usage data, and no information
about you. It is a download.

In this build no update endpoint is configured, so **nothing leaves your device at all.** The bundled
blocklist ships inside the APK and works offline.

There is no crash reporting, no telemetry, no remote logging, and no advertising SDK.

## What is stored, and where

Everything below lives in the app's private storage on your device.

| Store | Contents |
|---|---|
| Protection config | Which protections are on, the Instagram limit, the waiting period |
| Pending changes | The one queued weakening change, if any, and its deadline |
| Instagram budget | Today's date, seconds consumed, extra minutes granted, whether the override is spent |
| Notification quota | How many notifications today, when the last one was, template *identifiers* |
| Filter rules | The active rule-set version and your own blocklist/allowlist entries |
| Wellbeing aggregates | Per day: total foreground milliseconds per package, and a 24-slot hourly profile |

Cloud backup and device-to-device transfer are **disabled** for this app. Restoring an old snapshot
would be a way to roll protection back to a state with time still on it.

## What is never stored

This list is enforced by the code, not by policy:

- **Browsing history.** Not domains that were allowed, and not domains that were blocked. A blocked
  DNS lookup increments an anonymous counter and nothing else.
- **Search queries.** A search phrase is classified in memory and discarded.
- **Raw accessibility content.** A `UiNode` snapshot exists for the length of one detection pass. No
  message text, no captions, no titles, no usernames.
- **Instagram account identity.** The budget is keyed by device and date. There is no field for a
  username, and nothing reads one.
- **Collection names, video titles, or anything else on screen.**
- **Screenshots.** None are ever taken.

## The accessibility service

Scoped as tightly as Android allows. The service manifest lists exactly which packages may deliver
events to it: YouTube, YouTube Music, Instagram, and supported browsers. Events from your banking
app, your messenger, your password manager or your keyboard **never reach this process at all** —
that is enforced by the platform, not by NoScroll filtering afterwards.

Within those apps, detection works from view resource identifiers (`clips_viewer`, `reel_recycler`)
rather than the words on screen. That is both more reliable across languages and far less sensitive.

The one place text is read is a browser's address bar, to decide whether a page is blocked. The URL
is evaluated and discarded — never written to storage, never logged, never included in diagnostics.

## The VPN

The Explicit Content Shield uses `VpnService`, which makes Android display a key icon. That icon is
the platform's notice that a VPN is configured; it is not a sign of data leaving your device.

What the tunnel does: advertises a DNS server at a private address, routes **only that address** into
it, answers blocked lookups with NXDOMAIN, and forwards everything else to your network's own
resolver. Your ordinary traffic never enters the tunnel.

What it does not do: upload traffic, store history, intercept TLS, install a certificate authority,
log plaintext, or rewrite DNS answers for anything other than returning "no such name".

## Screen-time insights

Optional. They require Usage Access, which you grant by hand in Android settings, and every
protection works whether or not you do.

The data is aggregate: a date, a package name, a duration. Not which video, not which conversation,
not which website. It is stored for 12 months by default, configurable, and can be deleted at any
time from Settings.

Deleting it does **not** reset your Instagram budget or any pending protected change. Those live in
separate stores specifically so that a privacy action cannot double as a way around a protection.

## Notifications

Notifications never contain domains, search terms, Instagram content, account names, or anything
else identifying. The notification history stores template *identifiers* — `evening.summary`,
`instagram.within_plan` — so wording can be rotated. What a message said is regenerated from the
template at send time and never written down.

## Diagnostics

The diagnostics report contains service states, permission states, an enum for the current surface,
a rule-set version, and an error *category* such as `vpn_establish_failed`. It contains no usernames,
messages, collection names, video titles, search queries, domains, raw accessibility text, or network
data.

## Your controls, all immediate

None of these are subject to the protection waiting period:

- Revoke Usage Access (in Android settings)
- Turn off screen-time analytics
- Delete all wellbeing history
- Change how long history is kept
- Disable all optional notifications
- Uninstall the app

Making somebody wait 24 hours to delete their own data would be a dark pattern wearing a protection
costume. Protection delays exist to stop you loosening a *restriction* on impulse — not to hold your
data hostage.
