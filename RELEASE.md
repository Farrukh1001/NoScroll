# Releasing

## Signing key

Android identifies an app by its signing key. **If you lose the release key you cannot ship an update
that installs over an existing copy** — users would have to uninstall, losing their data, and install
fresh. Treat the key as irreplaceable, because it is.

### Generating one

```bash
./tools/generate-release-key.sh
```

The script creates `local-signing/noscroll-release.jks` and a matching
`local-signing/keystore.properties`, generating a strong random password rather than asking for one.
It refuses to overwrite an existing keystore.

`local-signing/` is git-ignored in its entirety — keystore and passwords alike. **Back it up
somewhere safe and offline.** A password manager entry plus an encrypted copy of the `.jks` on
separate media is a reasonable minimum.

### Using an existing key

Either point `local-signing/keystore.properties` at it:

```properties
storeFile=/absolute/path/to/your.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Or set environment variables, which is what CI uses:

```
NOSCROLL_KEYSTORE_FILE
NOSCROLL_KEYSTORE_PASSWORD
NOSCROLL_KEY_ALIAS
NOSCROLL_KEY_PASSWORD
```

The properties file wins if both are present. Passwords are never printed by the build; to check what
the build resolved, run `./gradlew :app:reportSigningSource`, which reports the source and alias only.

If no signing material is found, the release build is left unsigned rather than silently falling back
to the debug key.

## Building a release

```bash
./gradlew clean
./gradlew test
./gradlew lint
./gradlew assembleRelease
./gradlew packageDist
```

`packageDist` copies the signed APK to `dist/NoScroll-v1.0.0.apk` and writes
`dist/NoScroll-v1.0.0.apk.sha256` beside it.

## Verifying

```bash
# Signature (apksigner ships in the Android SDK build-tools)
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs dist/NoScroll-v1.0.0.apk

# Checksum
sha256sum -c dist/NoScroll-v1.0.0.apk.sha256
```

Verify before publishing, and publish the checksum alongside the APK so users can do the same.

## Tagging and publishing

```bash
git tag -a v1.0.0 -m "NoScroll 1.0.0"
git push origin v1.0.0
```

The release workflow builds from the tag, signs using GitHub Secrets, produces the APK and its
SHA-256, and attaches both to the release. Required secrets:

| Secret | Contents |
|---|---|
| `KEYSTORE_BASE64` | The `.jks` file, base64-encoded |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias |
| `KEY_PASSWORD` | Key password |

Secrets are never echoed, and the decoded keystore is written outside the workspace and removed
afterwards.

## Rule-set signing

Blocklist updates are verified by ECDSA P-256 signature before they can replace the bundled list.

To generate signing material for list development:

```bash
./tools/generate-rule-key.sh
```

This writes `local-signing/rule-signing-key.pem` (private — never commit it) and prints the
base64-encoded public key. Put that public key in `RuleRepository.UPDATE_PUBLIC_KEY_BASE64` and set
`UPDATE_URL` to your endpoint.

**Both are deliberately empty in this build.** No public update endpoint exists yet, and shipping a
placeholder URL with a placeholder key would be pretending the update channel works. The bundled list
ships in the APK and functions offline; the update path simply stays dormant until it is real.

To sign a rule set:

```bash
openssl dgst -sha256 -sign local-signing/rule-signing-key.pem rules.txt | base64 -w0
```

The signature covers the exact bytes of the file. The format is deliberately a flat text file rather
than JSON so that the bytes signed are the bytes on disk, with no serialiser in between that might
reorder a field and invalidate a signature.

## Release checklist

- [ ] `./gradlew clean test lint assembleRelease` passes
- [ ] Version bumped in `app/build.gradle.kts` (`versionCode` and `versionName`)
- [ ] `CHANGELOG.md` updated
- [ ] `dist/` APK and `.sha256` produced by `packageDist`
- [ ] `apksigner verify --print-certs` shows the expected certificate
- [ ] APK installs and launches on a physical device
- [ ] Accessibility service connects; VPN establishes; onboarding completes
- [ ] Signing key backed up and its backup verified
- [ ] Tag pushed
