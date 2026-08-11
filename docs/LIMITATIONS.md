# Limitations

Every meaningful gap in NoScroll, in one place. An app in this category that hides its gaps is worse
than useless, because a user who believes they are protected stops paying attention.

## Android platform

**The app can be uninstalled.** Deliberate. Any ordinary self-installed APK can be removed by whoever
holds the phone, and no amount of engineering changes that without device-owner provisioning, which
this app refuses to use. The waiting periods apply to changes made inside the app, not to Android's
Uninstall button.

**The accessibility service can be switched off.** Also by design; Android requires that toggle to be
reachable, and it should be.

**OEM battery management.** Samsung, Xiaomi, Oppo, Vivo, OnePlus, Huawei and Realme all kill
background services aggressively. NoScroll can detect that it stopped, but not prevent it. See
TROUBLESHOOTING.md.

**Notification timing.** WorkManager is best-effort and Doze can delay it. The alternative is
requesting exact-alarm permissions meant for alarm clocks, which would be a policy problem and a
battery one. The app says "around 10:00 PM" rather than claiming precision it does not have.

**One VPN at a time.** Using another VPN stops the filter. Fail-closed mode blocks supported browsers
when that happens rather than pretending coverage continues.

**Safe mode and work profiles** bypass everything.

## Content filtering

**Encrypted DNS is the big one.** DNS-over-HTTPS and DNS-over-TLS bypass the filter entirely, and
several browsers enable DoH by default. This is why the accessibility browser guard exists as a
second layer and why fail-closed mode exists at all. TROUBLESHOOTING.md explains how to turn DoH off.

**Hardcoded IP addresses.** An app or site that skips DNS is invisible to a DNS filter.

**Encrypted Client Hello** hides the hostname from any network-level filter.

**Mixed-content platforms.** A domain serving both ordinary and explicit content cannot be filtered
by name without blocking all of it. Reddit, Twitter/X and Telegram are the obvious cases: NoScroll
does not block them, and the explicit material they host is out of reach of this design.

**The bundled list is small.** About a hundred well-known domains, compiled for this project. It is a
baseline, not comprehensive. The signed update framework exists to grow it; no public endpoint is
configured yet, and the app does not pretend otherwise.

**Search classification is conservative by choice.** It requires an unambiguous term *and* the
absence of health, education, support, news or academic context. That means some explicit searches
get through. The alternative — blocking somebody looking up an STI symptom or a helpline — is a real
harm, and a missed block is not.

**Only sexually explicit material is targeted.** Gore, violence, horror, crime, ordinary manga and
manhwa, and mature fiction without explicit sexual content are all deliberately out of scope.

## Third-party app detection

**Detection depends on YouTube's and Instagram's view hierarchies.** They redesign; detection
degrades. NoScroll fails toward *not* blocking, so a redesign means Shorts might slip through — not
that your DMs get covered.

**Shorts masking can lag a fast scroll.** Overlays are repositioned on accessibility events, which
arrive slightly behind the frame.

**The Shorts navigation button is only covered when it can be located confidently.** Guessing at a
rectangle risks covering the wrong button, so when detection is unsure it covers nothing and relies
on the viewer-level block.

**Instagram surface detection has ambiguous cases.** Where it is unsure, it does not count and does
not block. Some Explore or Saved browsing may therefore go uncounted.

**Single-item mode needs the pager to report its position.** It reads `CollectionItemInfo` rather
than intercepting gestures — interception would require touch-exploration mode, which changes how
every app on the phone responds to touch. If the pager reports nothing, single-item mode stands down
rather than guessing.

**YouTube Music background audio.** Foreground blocking cannot reliably stop audio already playing,
especially over Bluetooth. Disabling or uninstalling the package is the real protection, and the app
says so instead of claiming the blocker is equivalent.

## Measurement

**Screen-time insights need Usage Access,** which is optional. Without it there are no totals, no
comparisons, and no calendar — but every protection still works.

**Usage events are approximate.** `UsageStatsManager` drops events across process boundaries and
reboots. The aggregator handles the known cases (see TESTING.md), but totals can differ slightly from
Android's own Digital Wellbeing.

**Blocked attempts are a count, never minutes.** There is no honest way to know how long a scroll
that never started would have lasted, so the app does not guess.

**"Time saved" needs a baseline.** Nothing is shown on the first day, and the seven-day average needs
at least three days of real history. Fabricating a first-day saving would be the easiest lie in this
whole app to tell.

## Clock and state

**Clock manipulation is resisted, not prevented.** Waits are anchored to the monotonic clock, so
moving the system clock forward buys nothing while the device is running. Across a reboot the wall
clock is used, crediting only forward movement — rolling it back gains nothing but also never locks
the user out.

**Cloud backup is disabled,** so protection state cannot be rolled back via a restore. The trade is
that settings do not follow you to a new phone.

## Verified where

Everything in `app/src/test` runs on any machine. Everything in `docs/DEVICE_TESTING.md` needs a
physical phone with the real apps installed, and has not been verified by writing this document.
