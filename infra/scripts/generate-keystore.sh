#!/usr/bin/env bash
# =============================================================================
# Generates a PKCS#12 keystore holding an RSA-2048 keypair, used by auth-service
# (Phase 1C) to sign JWTs and to publish the matching public key via JWKS.
#
# LOCAL DEVELOPMENT ONLY. The output is a self-signed key written to disk with a
# password you pass on the command line. Real environments must source signing
# keys from a managed secret store (AWS Secrets Manager / KMS, Vault), where the
# private key is never written to a developer machine and rotation is automated.
#
# Usage:
#   ./infra/scripts/generate-keystore.sh                      # defaults
#   ./infra/scripts/generate-keystore.sh -o infra/secrets/jwt-keystore.p12 \
#       -p mypassword -a jwt-signing -d 3650
#
# The output path is gitignored (infra/secrets/). Never commit the result.
# =============================================================================

set -euo pipefail

OUTPUT_PATH="infra/secrets/jwt-keystore.p12"
ALIAS="jwt-signing"
VALIDITY_DAYS=3650
KEY_SIZE=2048
DNAME="CN=acme-ecommerce-auth,OU=Platform,O=Acme,L=Local,C=GB"
STORE_PASSWORD=""
FORCE=false

usage() {
  sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

while getopts ":o:p:a:d:s:n:fh" opt; do
  case "${opt}" in
    o) OUTPUT_PATH="${OPTARG}" ;;
    p) STORE_PASSWORD="${OPTARG}" ;;
    a) ALIAS="${OPTARG}" ;;
    d) VALIDITY_DAYS="${OPTARG}" ;;
    s) KEY_SIZE="${OPTARG}" ;;
    n) DNAME="${OPTARG}" ;;
    f) FORCE=true ;;
    h) usage 0 ;;
    \?) echo "Unknown option: -${OPTARG}" >&2; usage 1 ;;
    :)  echo "Option -${OPTARG} requires an argument" >&2; usage 1 ;;
  esac
done

command -v keytool >/dev/null 2>&1 || {
  echo "ERROR: keytool not found on PATH. Install a JDK (this project targets Java 25)." >&2
  exit 1
}

# Refuse to silently replace an existing keystore: overwriting the signing key
# instantly invalidates every token already issued against it.
if [[ -f "${OUTPUT_PATH}" && "${FORCE}" != true ]]; then
  echo "ERROR: ${OUTPUT_PATH} already exists."
  echo "       Re-run with -f to overwrite. NOTE: replacing the key invalidates"
  echo "       every JWT signed with the current one."
  exit 1
fi

# Prompt rather than accept a default password: a keystore password baked into a
# script is the kind of thing that gets copied into a deployed environment.
if [[ -z "${STORE_PASSWORD}" ]]; then
  read -r -s -p "Keystore password (min 6 chars): " STORE_PASSWORD
  echo
  read -r -s -p "Confirm password: " CONFIRM_PASSWORD
  echo
  if [[ "${STORE_PASSWORD}" != "${CONFIRM_PASSWORD}" ]]; then
    echo "ERROR: passwords do not match." >&2
    exit 1
  fi
fi

if [[ "${#STORE_PASSWORD}" -lt 6 ]]; then
  echo "ERROR: keystore password must be at least 6 characters (keytool requirement)." >&2
  exit 1
fi

mkdir -p "$(dirname "${OUTPUT_PATH}")"
rm -f "${OUTPUT_PATH}"

echo "Generating RSA-${KEY_SIZE} keypair..."
echo "  keystore : ${OUTPUT_PATH}"
echo "  alias    : ${ALIAS}"
echo "  validity : ${VALIDITY_DAYS} days"

keytool -genkeypair \
  -alias "${ALIAS}" \
  -keyalg RSA \
  -keysize "${KEY_SIZE}" \
  -sigalg SHA256withRSA \
  -validity "${VALIDITY_DAYS}" \
  -dname "${DNAME}" \
  -storetype PKCS12 \
  -keystore "${OUTPUT_PATH}" \
  -storepass "${STORE_PASSWORD}" \
  -keypass "${STORE_PASSWORD}"

# Owner-read-only: the private key must not be world-readable.
chmod 600 "${OUTPUT_PATH}"

echo
echo "Done. Keystore written to ${OUTPUT_PATH} (mode 600)."
echo
echo "Verify with:"
echo "  keytool -list -v -keystore ${OUTPUT_PATH} -storetype PKCS12"
echo
echo "auth-service (Phase 1C) reads it via environment variables:"
echo "  PLATFORM_JWT_KEYSTORE_PATH=${OUTPUT_PATH}"
echo "  PLATFORM_JWT_KEYSTORE_ALIAS=${ALIAS}"
echo "  PLATFORM_JWT_KEYSTORE_PASSWORD=<the password you just set>"
echo
echo "Put the password in infra/.env (gitignored). Do NOT commit the keystore."
