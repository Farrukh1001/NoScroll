# Threat model

NoScroll is a tool you point at yourself. That single fact decides everything below.

## Who it is for

Someone who wants to keep using YouTube and Instagram but does not want to be pulled into infinite
scroll, and who is willing to accept friction they chose in advance. The "attacker" is that same
person, later, at 1 a.m., who wants the friction gone right now.

That is a real and defensible adversary — impulse is a genuine opponent, and a 24-hour delay beats it
most of the time. It is also a *cooperative* one. They installed this. They want it to work. The
design leans on that: friction rather than force, honesty rather than lock-in.

## Who it is not for

**Anyone trying to control someone else's phone.** NoScroll is not parental-control software and is
not built to survive a hostile device owner. Every restriction it imposes can be removed by
uninstalling the app, which anyone holding the phone can do in about fifteen seconds. If you deploy
this against another person's wishes, it will not hold, and it is not intended to.

## What it defends against

**Impulse.** The main one, and it works. Weakening a protection means seeing exactly what will
change, waiting hours, and coming back. Most impulses do not survive that. Shortening the delay
serves the old sentence, so the escape hatch cannot be widened quickly either.

**Accidental drift into a feed.** Masked Shorts shelves and Reel cards remove the entry points
entirely. A tap that would have started twenty minutes of scrolling lands on a mask instead.

**Underestimating time spent.** The Instagram budget is measured, shared across accounts, and cannot
be reset by switching profile, logging out, reinstalling Instagram, or changing the system clock.

**Casual exposure to explicit material.** A typed domain or an explicit search on a normal browser is
blocked at the DNS layer or by the browser guard.

## What it does not defend against

Stated plainly, because an app in this category that hides its gaps is worse than useless.

**Uninstalling.** By design. See README. There is no anti-uninstall behaviour and there will not be.

**Determined technical circumvention.** Turning off the accessibility service, revoking VPN consent,
booting to safe mode, using a work profile, or clearing app data all remove protection. Some of these
are noticed and surfaced as unhealthy; none are prevented.

**Encrypted DNS.** DNS-over-HTTPS and DNS-over-TLS bypass the filter entirely. Many browsers enable
DoH by default. This is the single largest gap in the shield, and it is why the accessibility browser
guard and fail-closed mode exist.

**Hardcoded IP addresses and Encrypted Client Hello.** Nothing that filters by name can see through
either.

**Mixed-content platforms.** A domain serving both ordinary and explicit content cannot be filtered
by name without blocking all of it.

**Another VPN.** Android allows one VPN at a time. Using a different one stops the filter. Fail-closed
mode blocks supported browsers when that happens rather than pretending everything is covered.

**Background audio.** Foreground blocking cannot reliably stop YouTube Music that is already playing,
particularly over Bluetooth. Disabling or uninstalling the package is the real protection, and the
app says so instead of claiming a win it has not earned.

**Third-party redesigns.** YouTube and Instagram change their UI. When detection breaks, it breaks
toward *not* blocking.

**A compromised device.** Root, malware, or a hostile ROM defeats everything here.

## Design consequences

**Fail open on detection, fail closed on the network.** An unrecognised screen is not blocked — the
cost of covering someone's messages is much higher than the cost of missing a Short. But if the
filter is *supposed* to be running and is not, supported browsers are blocked, because there the
silent failure is the dangerous one.

**Never claim protection that is not running.** `ProtectionHealth` is the single source of truth, and
onboarding refuses to mark setup complete over a disconnected service. A green tick over a dead
service is the most damaging thing this app could do, because the user stops checking.

**Store as little as possible.** The strongest privacy guarantee is having nothing to leak. There is
no browsing history to subpoena, no account to breach, and no server to compromise.

**No emergency bypass for explicit content.** Every other restriction has a documented escape hatch.
This one does not, because it is the category where a 1 a.m. override is least likely to be something
the morning self wanted.

## Security boundaries

**Exported components.** Two: the launcher activity and `ShortsLinkActivity`. The latter treats input
as hostile — an explicit host allowlist, an exact video-id shape, and anything unrecognised handed
back to the system unacted-on. All `PendingIntent`s are immutable.

**Rule updates.** Verified by ECDSA P-256 signature before activation, with a last-known-good
fallback, downgrade rejection and expiry checking. Cleartext traffic is disabled and user-added CA
certificates are not trusted. Bundled rules always work offline, so a hostile network can withhold an
update but never install one.

**Packet handling.** Every read is bounds-checked; malformed packets are forwarded untouched rather
than acted on. Only DNS reaches this code at all, because only the resolver address is routed.

**No secrets in the app.** There is nothing to steal — no API key, no token, no credential.

## Reporting

Security issues: see [SECURITY.md](SECURITY.md).

False positives in the content filter — especially anything medical, educational or support-related —
are treated as bugs of the same severity. See the README for how to report one without needing to
share what you were looking at.
