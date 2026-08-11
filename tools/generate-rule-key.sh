#!/usr/bin/env bash
#
# Generates an ECDSA P-256 key pair for signing blocklist updates, and prints the public key in the
# form RuleRepository expects.
#
# The private key stays in local-signing/ and must never be committed. The public key is compiled
# into the app, so anyone can verify that a rule set came from whoever holds this private key.
#
# P-256 rather than Ed25519: Signature.getInstance("Ed25519") only exists from Android 13, and
# NoScroll supports Android 8. An update mechanism that silently cannot verify on older devices would
# be worse than one using a slightly older, universally available curve.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SIGNING_DIR="$ROOT/local-signing"
PRIVATE_KEY="$SIGNING_DIR/rule-signing-key.pem"
PUBLIC_KEY="$SIGNING_DIR/rule-signing-public.der"

if ! command -v openssl >/dev/null 2>&1; then
    echo "error: openssl not found." >&2
    exit 1
fi

if [ -f "$PRIVATE_KEY" ]; then
    echo "error: $PRIVATE_KEY already exists." >&2
    echo "Replacing it invalidates every rule set signed with the old key." >&2
    exit 1
fi

mkdir -p "$SIGNING_DIR"
chmod 700 "$SIGNING_DIR" 2>/dev/null || true

openssl ecparam -name prime256v1 -genkey -noout -out "$PRIVATE_KEY"
chmod 600 "$PRIVATE_KEY" 2>/dev/null || true

openssl ec -in "$PRIVATE_KEY" -pubout -outform DER -out "$PUBLIC_KEY" 2>/dev/null

echo "Private key: $PRIVATE_KEY  (git-ignored — never commit this)"
echo
echo "Public key, base64 X.509 — put this in RuleRepository.UPDATE_PUBLIC_KEY_BASE64:"
echo
base64 -w0 < "$PUBLIC_KEY" 2>/dev/null || base64 < "$PUBLIC_KEY" | tr -d '\n'
echo
echo
echo "To sign a rule set:"
echo "  openssl dgst -sha256 -sign $PRIVATE_KEY rules.txt | base64 -w0"
echo
echo "The signature covers the exact bytes of the file, so do not reformat it after signing."
