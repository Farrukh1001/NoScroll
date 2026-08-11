## What this changes

<!-- One or two sentences. Link the issue if there is one. -->

## Why

<!-- The problem, not the patch. -->

## How it was verified

<!-- What you actually ran and what you saw. "Should work" is not verification. -->

- [ ] `./gradlew test` passes
- [ ] `./gradlew lint` passes
- [ ] `./gradlew assembleDebug` passes
- [ ] Tested on a physical device (say which, and which Android version)

## Tests

- [ ] Behaviour changes in `com.noscroll.domain` have a test that fails without this change
- [ ] No real explicit domains appear in test source (`adult.example` and friends only)

## The non-negotiables

Confirm each, or explain in the notes below why it does not apply:

- [ ] No anti-uninstall behaviour, device-admin use, or blocking of Android Settings
- [ ] Nothing new is stored: no browsing history, search terms, raw accessibility content,
      usernames, titles, or account identity
- [ ] Nothing is sent off the device
- [ ] The app never claims a protection is working when it is not
- [ ] Detection still fails toward *not* blocking; DMs and Stories remain untouchable
- [ ] Privacy controls remain immediate and are not subject to the waiting period
- [ ] Any change that weakens protection still goes through the protected-settings path

## New dependencies

- [ ] None

<!-- If any: name it, say what it does, and state its licence. NoScroll is GPL-3.0-only, so a
     GPL-incompatible licence is a blocker. -->

## Notes for the reviewer

<!-- Anything you are unsure about, or a decision worth arguing with. -->
