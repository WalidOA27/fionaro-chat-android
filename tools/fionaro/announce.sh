#!/usr/bin/env bash
# Post an announcement to the Fionaro announcement board as the server admin.
# See docs/fionaro_announcements.md.
#
#   tools/fionaro/announce.sh path/to/message.txt          # dry run (checks + preview)
#   tools/fionaro/announce.sh path/to/message.txt --send   # publish
#
# Env overrides: FIONARO_SSH_HOST (default: homelab), FIONARO_ROOM_ID, FIONARO_SYNAPSE_DB, FIONARO_HOMESERVER_URL.
# The root password of the homeserver host is asked interactively (or read from FIONARO_ROOT_PASSWORD).
set -euo pipefail

msg=${1:?usage: announce.sh <message-file> [--send]}
mode=${2:-}
[[ -z "$mode" || "$mode" == "--send" ]] || { echo "unknown option: $mode" >&2; exit 2; }
[[ -s "$msg" ]] || { echo "empty or missing message file: $msg" >&2; exit 2; }

host=${FIONARO_SSH_HOST:-homelab}
room=${FIONARO_ROOM_ID:-'!cBndmQDEDywPmRQzqI:fionaro.pw'}
db=${FIONARO_SYNAPSE_DB:-/home/walid/comunicacion/matrix/synapse/config/homeserver.db}
hs=${FIONARO_HOMESERVER_URL:-https://matrix.fionaro.pw}
here=$(cd "$(dirname "$0")" && pwd)

if [[ -z "${FIONARO_ROOT_PASSWORD:-}" ]]; then
  read -rsp "root password on $host: " FIONARO_ROOT_PASSWORD; echo
fi

remote=$(ssh "$host" 'mktemp -d')
cleanup() { ssh "$host" "rm -rf '$remote'" >/dev/null 2>&1 || true; }
trap cleanup EXIT
scp -q "$here/announce_remote.py" "$host:$remote/announce_remote.py"
scp -q "$msg" "$host:$remote/message.txt"

printf '%s\n' "$FIONARO_ROOT_PASSWORD" | ssh "$host" \
  "su -c 'python3 $remote/announce_remote.py \"$db\" \"$hs\" \"$room\" $remote/message.txt $mode' root" 2>&1 \
  | sed 's/^Password: *//'
