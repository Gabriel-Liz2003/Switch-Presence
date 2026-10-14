#!/usr/bin/env bash
# Download owner's licensed Discord Social SDK from a private repository.
# The mobile browser can upload two chunks (<25 MiB each) instead of one large AAR.
set -euo pipefail

if ! [[ "${DISCORD_SDK_PRIVATE_REPO:-}" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]]; then
  echo "::error::Set DISCORD_SDK_PRIVATE_REPO as owner/private-repo."
  exit 1
fi
if [ -z "${DISCORD_SDK_READ_TOKEN:-}" ]; then
  echo "::error::Set DISCORD_SDK_READ_TOKEN with read-only access to the private repository."
  exit 1
fi

folder="${1:-app/libs}"
mkdir -p "$folder"
fetch_part() {
  local name="$1"
  curl --fail --location --retry 2 --silent --show-error \
    --header "Authorization: Bearer $DISCORD_SDK_READ_TOKEN" \
    --header "Accept: application/vnd.github.raw+json" \
    --header "X-GitHub-Api-Version: 2022-11-28" \
    "https://api.github.com/repos/$DISCORD_SDK_PRIVATE_REPO/contents/$name" \
    --output "$folder/$name"
}

fetch_part discord_partner_sdk.aar.part01
fetch_part discord_partner_sdk.aar.part02
cat "$folder/discord_partner_sdk.aar.part01" \
    "$folder/discord_partner_sdk.aar.part02" > "$folder/discord_partner_sdk.aar"
rm -f "$folder/discord_partner_sdk.aar.part01" "$folder/discord_partner_sdk.aar.part02"

# Pinned to the verified owner's SDK 1.10.19337, for tamper detection.
printf '%s  %s\n' \
  'b1b2491f1e1848c79fd6f1986d5aa1e0c8019e88a6e62c7be06b89e8e4870933' \
  "$folder/discord_partner_sdk.aar" | sha256sum --check --status || {
  echo "::error::Discord Social SDK hash mismatch; refusing to build."
  exit 1
}
python3 scripts/verify_sdk.py "$folder/discord_partner_sdk.aar"
