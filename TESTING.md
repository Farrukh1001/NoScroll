# Testing

## Running everything

```bash
./gradlew test           # JVM unit tests — the bulk of the suite
./gradlew lint           # Android lint
./gradlew assembleDebug  # debug APK
./gradlew connectedAndroidTest   # instrumentation tests; needs a device or emulator
```

Reports land in `app/build/reports/`.

## Strategy

The rules live in pure Kotlin, so they are tested as pure Kotlin.

Everything in `com.noscroll.domain` — what counts as weakening a setting, when the Instagram clock
runs, whether a domain matches a rule, how a DNS packet parses, how usage events aggregate — has no
Android imports. That is not an aesthetic choice. It means "a pending change survives a clock
rollback across a reboot" is a three-line unit test rather than an instrumentation test that needs a
device and a way to fake the system clock.

Two seams make this work:

**`TimeSource`** exposes wall-clock and monotonic time separately, so a test can move either one
independently — forward, backward, or reset as if the device rebooted.

**`UiNode`** is a plain snapshot of an accessibility tree, so detector tests build the exact awkward
case by hand: a Reel opened from a DM, a Shorts shelf that is not the Shorts player, a Stories view
that must not be mistaken for Reels.

## What is covered

### Protected settings — `ProtectedSettingsEngineTest`

Every delay preset. Strengthening applies immediately; weakening queues. Mixed requests split
correctly. Only one bundle pending at a time, and a rejected request applies nothing. Cancellation
works and starts a cooldown. Wall-clock jumps forward do not shorten a wait. Time with the device off
*is* credited across a reboot; a backwards clock credits nothing and never locks the user out.
Shortening the delay serves the old delay (48h → 1h still waits 48h). The delay changes at most once
per rolling 24 hours and must be requested on its own. Out-of-range values are refused.

### Instagram — `InstagramBudgetTest`, `EmergencyExtensionTest`

The 30-minute default. The clock runs only when all four conditions hold. Backwards and implausibly
long slices are discarded. Exhaustion at exactly zero. Rollover is idempotent and clears the daily
override; a pending limit increase lands the next day. Lowering the limit bites today, raising it
waits for tomorrow. The shared-budget acceptance case from the specification — 20 minutes on account
A, 10 remaining on account B, override spent once — is a test.

Extension: 1, 5, 17 and 30 accepted; 0, −1, 31, decimals, text, `+5` and non-ASCII digits rejected.
Sentence matching forgives outer whitespace and nothing else. The countdown pauses when backgrounded,
survives as data, and an incomplete countdown blocks the grant. A failed attempt consumes nothing.

### Detection — `DetectorTest`

Shorts viewer versus normal watch page versus a Shorts shelf on Home. Shelf and nav-button bounds for
masking, and *nothing* returned when the nav bar cannot be found confidently. Instagram's
clips/reel naming trap in both directions. DMs never budgeted or blocked; DM search not confused with
Explore. Provenance carrying a Collection item's budget status across navigation. Unrecognised
screens returning LOW confidence, which never triggers an action.

Shorts links: every supported host parsed; hostile inputs (`youtube.com.evil.example`,
`javascript:`, `file://`, wrong-length ids) refused; the isolated URL carrying no playlist.

### Content filter — `FilterTest`, `RuleUpdateTest`, `DnsAndIpTest`

Filter tests use reserved example domains (`adult.example`), never real explicit hosts.

Normalisation folds case, trailing dots, ports, credentials and Unicode/IDN; malformed hostnames are
refused rather than guessed at. Rule matching is label-wise, so `notadult.example` does not match a
rule for `adult.example`. The allowlist wins over a broader block rule.

Search classification: pornographic phrases blocked; **health, education, support, news and academic
searches explicitly allowed**; gore, violence, crime, horror and ordinary manga/manhwa never targeted;
word-boundary matching so "Essex" and "Scunthorpe" survive.

Rule updates: correct signatures verify; tampered payloads, foreign keys, empty and garbage
signatures fail; truncated downloads caught by the declared count; expired and downgraded sets
refused; version comparison component-wise rather than lexical.

Packets: DNS names parsed from IPv4 and IPv6 UDP; truncated, fragmented, non-UDP, compression-pointer
and two-question inputs all return null so the packet is forwarded untouched; NXDOMAIN responses echo
the question with correct flags; IPv4 header checksums verify to zero and IPv6 UDP checksums are
never zero.

### Wellbeing — `UsageAggregatorTest`, `TimeSavedTest`

Missing background events closed by the next foreground. Multiple activities of one package not
double-counted. Screen-off and shutdown closing intervals. Midnight splits. Unclosed intervals capped
at the window end rather than running forever. Out-of-order events sorted. A DST transition creating
or losing no time.

Time saved: a real reduction reported with the specification's exact wording; higher usage stated
factually with no shame language and never as a negative saving; **no saving on the first day**; the
seven-day average requiring a minimum of real history; and a partial day never compared against a
full one. Blocked attempts reported as a count, never converted into minutes.

### Notifications — `NotificationQuotaTest`

Default of two, configurable 0–3, hard maximum of three enforced regardless of stored value. The
evening summary's reserved slot, and a protection failure outranking it. Minimum spacing. Same-kind
dedupe per day. Seven-day template rotation, with a fallback when nothing else is available. Quota
exhaustion updating the persistent notification instead of posting a fourth. Midnight reset clearing
the count while template history survives.

### Scheduling — `SchedulingTest`

Delay arithmetic for the evening reminder across midnight, both DST transitions, and multiple
timezones. Clamping instead of throwing. Never negative. There is no exact-alarm permission to test
because none is requested.

### Achievements — `AchievementsTest`

A fresh install earns nothing. Days inside the goal counted over completed days only, never today.
Streaks of five and seven, and a broken run ending the streak. A personal best requiring a week of
baseline. A reduction measured across two full weeks, with an increase never dressed up as a
reduction and a 2% difference treated as noise rather than an accomplishment.

### UI — `app/src/androidTest`

Compose tests for the Today dashboard, the pending-change bundle, the extension flow, empty and
permission-denied states, dark mode, large fonts and screen-reader descriptions. These need a device
or emulator:

```bash
./gradlew connectedDebugAndroidTest
```

22 tests, verified on an API 36 emulator.

## Conventions

- Real explicit domains never appear in test source. Use `adult.example` and friends.
- Tests assert on observable behaviour, not on how something is implemented.
- Every bug fixed gets a test that fails without the fix.
- Test names are sentences describing the rule, so a failure report reads as a list of broken
  promises.
