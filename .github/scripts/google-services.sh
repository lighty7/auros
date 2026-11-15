#!/usr/bin/env bash
# Writes app/google-services.json from the GOOGLE_SERVICES_JSON secret.
#   --strict  fail when the secret is missing (releases)
#   default   fall back to a stub so the app still compiles (pull requests from forks get no secrets)
set -euo pipefail

out="app/google-services.json"

if [[ -n "${GOOGLE_SERVICES_JSON:-}" ]]; then
  printf '%s' "$GOOGLE_SERVICES_JSON" > "$out"
  python3 - "$out" <<'PY'
import json, sys
data = json.load(open(sys.argv[1]))
pkgs = [c["client_info"]["android_client_info"]["package_name"] for c in data.get("client", [])]
if "com.aura.app" not in pkgs:
    sys.exit(f"::error::GOOGLE_SERVICES_JSON has no client for com.aura.app (found: {pkgs})")
if not any(o.get("client_type") == 3 for c in data["client"] for o in c.get("oauth_client", [])):
    sys.exit("::error::GOOGLE_SERVICES_JSON has no web OAuth client. Enable Google sign-in in Firebase and download the file again.")
PY
  echo "Firebase config written from the secret."
  exit 0
fi

if [[ "${1:-}" == "--strict" ]]; then
  echo "::error::The GOOGLE_SERVICES_JSON secret is not set. Paste the contents of app/google-services.json into it."
  exit 1
fi

echo "::warning::GOOGLE_SERVICES_JSON is not set; building with a stub config. Sign-in and the feed won't work in this build."
cat > "$out" <<'JSON'
{
  "project_info": { "project_number": "000000000000", "project_id": "aura-ci-stub", "storage_bucket": "aura-ci-stub.appspot.com" },
  "client": [{
    "client_info": { "mobilesdk_app_id": "1:000000000000:android:0000000000000000", "android_client_info": { "package_name": "com.aura.app" } },
    "oauth_client": [{ "client_id": "000000000000-stub.apps.googleusercontent.com", "client_type": 3 }],
    "api_key": [{ "current_key": "stub" }],
    "services": { "appinvite_service": { "other_platform_oauth_client": [{ "client_id": "000000000000-stub.apps.googleusercontent.com", "client_type": 3 }] } }
  }],
  "configuration_version": "1"
}
JSON
