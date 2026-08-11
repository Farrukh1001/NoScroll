#!/usr/bin/env bash
#
# Generates a release signing key for NoScroll.
#
# The key and its password go in local-signing/, which is git-ignored in its entirety. Losing that
# directory means losing the ability to update an installed copy of the app in place, so back it up
# somewhere safe and offline before you ship anything with it.
#
# The password is generated here rather than typed, so it is strong and never passes through shell
# history. It is written only to local-signing/keystore.properties.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SIGNING_DIR="$ROOT/local-signing"
KEYSTORE="$SIGNING_DIR/noscroll-release.jks"
PROPERTIES="$SIGNING_DIR/keystore.properties"
ALIAS="noscroll"

# Prefer JAVA_HOME so this works on Windows (where the binary is keytool.exe and is often not on
# PATH) and on machines with several JDKs installed.
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/keytool" ]; then
    KEYTOOL="$JAVA_HOME/bin/keytool"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/keytool.exe" ]; then
    KEYTOOL="$JAVA_HOME/bin/keytool.exe"
elif command -v keytool >/dev/null 2>&1; then
    KEYTOOL="keytool"
else
    echo "error: keytool not found. Install a JDK 17+, or set JAVA_HOME to one." >&2
    exit 1
fi

# Never silently replace a key that might be signing a published build.
if [ -f "$KEYSTORE" ]; then
    echo "error: $KEYSTORE already exists." >&2
    echo "Refusing to overwrite it — replacing a release key breaks in-place updates." >&2
    echo "Move it aside deliberately if you really mean to generate a new one." >&2
    exit 1
fi

mkdir -p "$SIGNING_DIR"
chmod 700 "$SIGNING_DIR" 2>/dev/null || true

# A 32-character password from the system CSPRNG. Generated rather than typed, so it is strong and
# never passes through shell history.
PASSWORD="$(head -c 48 /dev/urandom | base64 | tr -d '\n/+=' | cut -c1-32)"
if [ ${#PASSWORD} -lt 24 ]; then
    echo "error: could not generate a password of sufficient length." >&2
    exit 1
fi

echo "Generating a 4096-bit RSA release key valid for 30 years..."
"$KEYTOOL" -genkeypair \
    -keystore "$KEYSTORE" \
    -alias "$ALIAS" \
    -keyalg RSA \
    -keysize 4096 \
    -validity 10950 \
    -storepass "$PASSWORD" \
    -keypass "$PASSWORD" \
    -dname "CN=NoScroll, OU=NoScroll, O=NoScroll, L=Unknown, ST=Unknown, C=ZZ" \
    -storetype PKCS12 \
    >/dev/null

cat > "$PROPERTIES" <<EOF
# NoScroll release signing material.
#
# This file and the keystore beside it are git-ignored. Back BOTH up somewhere safe and offline:
# losing them means you can never ship an update that installs over an existing copy of the app.
storeFile=local-signing/noscroll-release.jks
storePassword=$PASSWORD
keyAlias=$ALIAS
keyPassword=$PASSWORD
EOF

chmod 600 "$PROPERTIES" "$KEYSTORE" 2>/dev/null || true

echo
echo "Created:"
echo "  $KEYSTORE"
echo "  $PROPERTIES"
echo
echo "The password is in keystore.properties and is not printed here or written to your shell"
echo "history. Back up local-signing/ now — it cannot be regenerated."
echo
echo "Verify with:  ./gradlew :app:reportSigningSource"
