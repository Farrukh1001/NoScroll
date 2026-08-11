# Contributing

Thanks for considering it. NoScroll is a small, opinionated app, so this document is mostly about
which opinions are load-bearing.

## Ground rules

**Honesty about protection is non-negotiable.** The app must never claim a protection is working when
it is not. No green tick over a disconnected service, no "setup complete" while something essential is
broken, no estimated numbers presented as measured ones. A user who stops trusting the status display
stops checking it, and then the app is worse than nothing.

**No anti-uninstall behaviour.** Not now, not behind a flag, not as an option. NoScroll does not block
Settings, cover the uninstall dialog, impersonate device management, or use device-owner provisioning.
Patches that add any of it will be declined. The reasoning is in
[THREAT_MODEL.md](THREAT_MODEL.md).

**Fail toward not blocking.** An unrecognised screen is not blocked. The cost of covering someone's
messages is much higher than the cost of missing a Short. The one exception is the network filter,
where a silent failure is the dangerous one, so it fails closed instead.

**Store as little as possible.** No browsing history, no search terms, no raw accessibility content,
no usernames, no titles. If a feature needs any of those to work, it needs a different design.

**No dark patterns.** No shame language, no fake urgency, no manipulative streaks, no "are you sure
you want to be lazy" dialogs. Privacy controls are never delayed.

## Getting set up

```bash
git clone https://github.com/YOUR-USERNAME/NoScroll.git
cd NoScroll
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew test
```

JDK 17+ and Android SDK platform 36.

## Before opening a pull request

```bash
./gradlew test lint assembleDebug
```

All three must pass. If you changed anything in `com.noscroll.domain`, there should be a test that
fails without your change.

## Code style

- Kotlin official style. Four spaces, 120-column soft limit.
- **Rules go in `com.noscroll.domain`, with no Android imports.** That is what makes them testable,
  and it is the single most important convention in this codebase.
- Comment the *why*, not the *what*. `// increment counter` helps nobody; `// A gap this long means
  the process restarted, not that somebody scrolled for an hour` is worth its line.
- Never use real explicit domains in tests. `adult.example` and friends exist for this.
- Test names are sentences describing the rule, so a failing suite reads as a list of broken
  promises.

## What is especially welcome

- **False-positive reports for the content filter**, particularly anything medical, educational,
  sexual-health, academic, news or abuse-prevention. These are treated as serious bugs. You do not
  need to share the domain or the search — the category and rough context are enough, and the app
  keeps no record you could attach anyway.
- **Detection fixes after a YouTube or Instagram redesign**, with the app version you saw it on.
- **Accessibility improvements**: TalkBack labels, contrast, large-text layouts, reduced motion.
- **Translations**, once the string resources settle.
- **Documented limitations you can close**, or ones you found that are not yet written down.

## What will probably be declined

- Anti-uninstall or device-admin functionality, in any form
- Telemetry, analytics, crash reporting, or anything that phones home
- Blocking categories outside the stated scope — gore, violence, crime, ordinary manga and manhwa are
  deliberately not targeted
- Features that require storing browsing history, search terms, or account identity
- An emergency bypass for explicit content
- Streaks, leaderboards, or social comparison
- Large dependencies that would need auditing for the value they add

None of these are personal. They are the shape of the product.

## Reporting bugs

Use the issue templates. The diagnostics report in Settings is sanitised — service states, permission
states, a surface enum, a rule-set version, an error category — and safe to paste.

Security issues go through private reporting instead: see [SECURITY.md](SECURITY.md).

## Licence

By contributing you agree your work is licensed under GPL-3.0-only, like the rest of the project.
