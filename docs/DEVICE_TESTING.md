# Device testing checklist

Everything below needs a physical Android phone with real YouTube, YouTube Music and Instagram
installed and signed in. None of it can be verified on a build machine, and the app should not be
described as working until somebody has walked through it.

Install with `adb install -r dist/NoScroll-v1.0.0.apk`.

## Setup

- [ ] Onboarding runs through all ten steps without crashing
- [ ] The accessibility toggle is reachable — if greyed out, "Allow restricted settings" fixes it
- [ ] The Finish button stays **disabled** while the accessibility service is off, and enables once
      it connects (this is the honesty guarantee; if it lets you finish, that is a serious bug)
- [ ] The VPN consent dialog appears and the tunnel establishes
- [ ] Android shows the key icon and the quiet persistent notification
- [ ] Usage Access can be granted and skipped, and skipping leaves every protection working

## YouTube

- [ ] A normal video plays with **nothing** overlaid — no mask, no blocker, no flicker
- [ ] Scrolling Home covers Shorts shelves with "Shorts hidden."
- [ ] Masks move or disappear as the feed scrolls, and do not detach or linger
- [ ] The Shorts navigation button is covered and cannot be tapped through
- [ ] Opening a Short (via search or a link) steps back once, then shows the blocker
- [ ] "Return to YouTube" lands on a normal YouTube screen
- [ ] YouTube is never force-stopped, and its back stack is not corrupted
- [ ] Leaving YouTube removes every overlay
- [ ] A `youtube.com/shorts/…` link from a messaging app opens the normal watch page, and swiping up
      does **not** produce another Short

## YouTube Music

- [ ] With it enabled, opening it shows the blocker immediately
- [ ] "Return Home" works; "Disable YouTube Music permanently" opens App Info
- [ ] There is no way to proceed into the app
- [ ] After disabling it, the Protect tab reports "Disabled"
- [ ] With it uninstalled, the Protect tab reports "Not installed"
- [ ] Note honestly whether audio already playing over Bluetooth continues — it is expected to, and
      that is why disabling is recommended

## Instagram — the exclusions matter most

- [ ] **DMs are never covered and never counted.** Open the inbox, scroll a conversation, search
      within DMs, send a message. The budget must not move.
- [ ] **Stories are never covered and never counted.** Watch several. The budget must not move.
      (This is the one most likely to break: Instagram calls Stories "reel" internally.)
- [ ] Reels count; the budget decreases while watching
- [ ] Explore counts; search discovery counts; DM search does **not**
- [ ] Saved and Collections count, and a post opened from a Collection keeps counting
- [ ] The remaining-time banner appears over Collections without covering controls
- [ ] Reel cards in the Home feed are masked; ordinary photos and carousels are not
- [ ] A Reel opened from a DM plays; swiping to a second one returns to the conversation
- [ ] Playback, sound and Back all work normally inside that single Reel

### Budget behaviour

- [ ] Locking the screen stops the clock
- [ ] Backgrounding Instagram stops the clock
- [ ] Force-stopping Instagram and reopening does **not** reset the budget
- [ ] Switching to a second Instagram account continues the same budget
- [ ] Rebooting mid-day preserves consumed time
- [ ] Moving the system clock forward does not add time
- [ ] At zero: the blocker appears on discovery surfaces, and DMs and Stories still open
- [ ] Instagram as a whole is never blocked

### Emergency extension

- [ ] Unavailable while the base budget has time left
- [ ] 0, 31, `-1`, `1.5` and text are all rejected
- [ ] The countdown pauses when the app is backgrounded and when the screen locks
- [ ] Rotating the device does not reset it
- [ ] Killing and reopening NoScroll restores the flow rather than granting time
- [ ] A wrong sentence is refused and consumes nothing
- [ ] The correct sentence grants exactly the chosen minutes
- [ ] A second attempt the same day is refused

## Explicit Content Shield

Use a known adult domain for this; do not record which one anywhere.

- [ ] A blocked domain fails to resolve in Chrome
- [ ] The same in a private/incognito tab
- [ ] An explicit search shows the blocker and steps back
- [ ] **A sexual-health search is not blocked** (e.g. an NHS symptom page)
- [ ] **A gore/violence/crime search is not blocked**
- [ ] **An ordinary manga or manhwa site is not blocked**
- [ ] A Google search gains `safe=active`
- [ ] Ordinary browsing, streaming and app connectivity are unaffected
- [ ] Turning the VPN off makes the Protect tab report "Not running"
- [ ] With fail-closed on, supported browsers are blocked while it is down
- [ ] Turning it back on restores browsing
- [ ] Note whether the browser uses DNS-over-HTTPS — if so, the filter is bypassed, as documented

## Protected settings

- [ ] Enabling a protection applies immediately
- [ ] Disabling one queues, and the toggle stays on until the wait elapses
- [ ] The pending bundle shows current value, requested value, reason, and remaining time
- [ ] A second weakening request is refused while one is pending
- [ ] Cancelling works instantly; a retry immediately after is refused (cooldown)
- [ ] Moving the clock forward does not apply a pending change early
- [ ] Rebooting preserves the pending change and credits the time the device was off
- [ ] Changing 48h → 1h waits 48 hours
- [ ] A second delay change within 24 hours is refused

## Privacy controls

- [ ] Turning analytics off takes effect immediately
- [ ] Deleting screen-time history takes effect immediately
- [ ] **After deleting history, the Instagram budget and any pending change are unchanged**
- [ ] Notifications can be set to 0 and none arrive
- [ ] The diagnostics report contains no usernames, messages, titles, searches or domains

## Notifications

- [ ] At most two arrive in a day by default; at most three when set to 3
- [ ] The evening summary arrives around the configured time (may be shifted — expected)
- [ ] Its content matches what the Today screen shows

## Robustness

- [ ] Rotating the device on every screen
- [ ] Largest system font size on every screen
- [ ] Dark mode on every screen
- [ ] TalkBack can read the dashboard, the status rows and the charts
- [ ] Killing NoScroll from recents and reopening restores state
- [ ] Rebooting restores protection without reopening the app
- [ ] Updating the APK over an existing install preserves state
- [ ] Changing the device timezone rolls the day over correctly
- [ ] Leaving it running a full day: no stale overlays, no battery complaint from the OS

## Uninstall

- [ ] Settings opens normally at all times
- [ ] Uninstall is never blocked or covered
- [ ] Uninstalling removes everything cleanly
