#!/usr/bin/env bash
#
# Verify the debug APK's manifest and dependency surface.
#
#   make verify
#
# Checks:
#   - RECORD_AUDIO, POST_NOTIFICATIONS, microphone FGS type are present
#   - INTERNET permission is absent
#   - no Google Play services / GMS classes or components
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
IMAGE="sound-recorder-builder"
APK="app/build/outputs/apk/debug/app-debug.apk"

if [[ ! -f "${PROJECT_DIR}/${APK}" ]]; then
    echo ">> ${APK} does not exist; run 'make debug' first." >&2
    exit 1
fi

if ! podman image exists "${IMAGE}"; then
    echo ">> Building container image '${IMAGE}'..."
    podman build -t "${IMAGE}" -f "${PROJECT_DIR}/Containerfile" "${PROJECT_DIR}"
fi

podman run --rm \
    --userns=keep-id \
    -v "${PROJECT_DIR}":/workspace:Z \
    -w /workspace \
    -e HOME=/tmp \
    "${IMAGE}" \
    bash -c '
set -euo pipefail
APK="${1}"
MANIFEST=/tmp/sound-recorder-manifest.xml
apkanalyzer manifest print "${APK}" > "${MANIFEST}"

fail() { echo "VERIFY FAILED: $*" >&2; exit 1; }

grep -q "android.permission.RECORD_AUDIO" "${MANIFEST}" \
    || fail "RECORD_AUDIO permission missing"
grep -q "android.permission.FOREGROUND_SERVICE" "${MANIFEST}" \
    || fail "FOREGROUND_SERVICE permission missing"
grep -q "android.permission.FOREGROUND_SERVICE_MICROPHONE" "${MANIFEST}" \
    || fail "FOREGROUND_SERVICE_MICROPHONE permission missing"
grep -q "android.permission.POST_NOTIFICATIONS" "${MANIFEST}" \
    || fail "POST_NOTIFICATIONS permission missing"
grep -q "android.permission.WAKE_LOCK" "${MANIFEST}" \
    || fail "WAKE_LOCK permission missing"
if ! grep -qE "android:foregroundServiceType=\"(microphone|0x80)\"" "${MANIFEST}"; then
    fail "microphone foregroundServiceType missing"
fi

if grep -q "android.permission.INTERNET" "${MANIFEST}"; then
    fail "INTERNET permission must not be present"
fi
if grep -q "android.permission.ACCESS_NETWORK_STATE" "${MANIFEST}"; then
    fail "ACCESS_NETWORK_STATE permission must not be present"
fi

if grep -q "com.google.android.gms" "${MANIFEST}"; then
    fail "Google Play services component found in manifest"
fi

apkanalyzer dex packages "${APK}" > /tmp/sound-recorder-dex.txt 2>/dev/null || true
if grep -q "com.google.android.gms" /tmp/sound-recorder-dex.txt; then
    fail "Google Play services classes found in APK"
fi
if grep -q "com.google.firebase" /tmp/sound-recorder-dex.txt; then
    fail "Firebase classes found in APK"
fi

echo "OK: manifest permissions, microphone FGS and no-GMS checks passed."
echo "APK: ${APK}"
' _ "${APK}"
