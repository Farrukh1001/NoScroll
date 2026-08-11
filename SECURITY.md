# Security policy

## Supported versions

| Version | Supported |
|---|---|
| 1.0.x | Yes |

## Reporting a vulnerability

Please report security issues privately through GitHub's **Security → Report a vulnerability**
(private vulnerability reporting) rather than opening a public issue.

Include what you can:

- What the issue is and roughly how severe you think it is
- Steps to reproduce, or a proof of concept
- Affected version and Android version
- Anything you already know about a fix

**Do not include personal data in a report.** In particular, do not attach browsing history, search
terms, screenshots of messages, or anything else private. NoScroll deliberately keeps none of that,
and a report should not be the thing that creates a record of it.

You can expect an acknowledgement within a few days and an assessment within two weeks. Fixes for
confirmed issues ship as soon as they are ready, and reporters are credited in the changelog unless
they prefer otherwise.

## What counts as a security issue here

**In scope:**

- The rule-update verifier accepting an unsigned, tampered, expired, or downgraded rule set
- The VPN service leaking traffic, storing history, or breaking ordinary connectivity
- Any path that writes browsing data, search terms, accessibility content, or account identity to
  disk or to the network
- `ShortsLinkActivity` acting on a URL it should have rejected
- A protected setting weakening without serving its waiting period
- The Instagram budget being reset or bypassed by anything other than the documented daily rollover
- Any exported component doing something it should not
- Dependency vulnerabilities that are actually reachable from this app

**Not security issues** — these are documented behaviour, and the reasoning is in
[THREAT_MODEL.md](THREAT_MODEL.md):

- The app can be uninstalled. This is deliberate and will not change.
- Disabling the accessibility service or revoking VPN consent removes protection.
- DNS-over-HTTPS, DNS-over-TLS, hardcoded IPs and Encrypted Client Hello bypass the DNS filter.
- Another VPN app displaces this one.
- A YouTube or Instagram redesign degrades detection.
- Background or Bluetooth audio from YouTube Music is not reliably stopped by foreground blocking.
- A rooted or otherwise compromised device defeats all of it.

## Filter false positives

If the shield blocks something it should not — especially medical, sexual-health, educational,
academic, news or abuse-prevention material — that is a bug, and a serious one. It does not need the
private reporting channel; a normal issue is fine.

You do not need to include the domain or the search. Describing the category and what you were doing
is enough, and the app keeps no record you could attach even if you wanted to.

## Security properties of this build

- No backend, no accounts, no telemetry, no remote logging, no advertising SDK.
- Cleartext traffic disabled; user-added CA certificates not trusted.
- Rule updates verified by ECDSA P-256 signature, with last-known-good fallback and downgrade
  rejection. Bundled rules always work offline.
- Two exported components; input treated as hostile. All `PendingIntent`s immutable.
- Cloud backup and device-transfer disabled, so protection state cannot be rolled back via a restore.
- No `QUERY_ALL_PACKAGES`, no `SYSTEM_ALERT_WINDOW`, no exact-alarm permissions.
- The accessibility service is restricted by manifest to the packages it protects.
- No secrets in the app, and no signing material in the repository.
