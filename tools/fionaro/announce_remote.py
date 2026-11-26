#!/usr/bin/env python3
# Runs ON the homeserver host, as root (it reads the Synapse SQLite database read-only).
# Usage: announce_remote.py <db> <homeserver_url> <room_id> <message_file> [--send]
# Without --send it only prints the room checks (dry run).
import json
import sqlite3
import sys
import urllib.parse
import urllib.request
import uuid

db, homeserver, room, msg_file = sys.argv[1:5]
send = "--send" in sys.argv[5:]
base = homeserver.rstrip("/") + "/_matrix/client/v3"

c = sqlite3.connect("file:%s?mode=ro" % db, uri=True)


def state(event_type):
    row = c.execute(
        "select j.json from current_state_events e join event_json j using(event_id) "
        "where e.room_id=? and e.type=? and e.state_key=''",
        (room, event_type),
    ).fetchone()
    return json.loads(row[0])["content"] if row else None


name = (state("m.room.name") or {}).get("name")
encrypted = state("m.room.encryption") is not None
power = state("m.room.power_levels") or {}
admins = [u for u, level in power.get("users", {}).items() if level >= 100]
print("room:", room, "|", name)
print("encrypted:", encrypted)
print("admins:", admins)
if encrypted:
    sys.exit("refusing: the room is encrypted, a plain API post would not be readable")
if not admins:
    sys.exit("no admin (power level 100) in the room")

body = open(msg_file, encoding="utf8").read().strip()
print("--- message (%d chars) ---\n%s\n---" % (len(body), body))
if not send:
    print("dry run: add --send to publish")
    sys.exit()

user = admins[0]
token = c.execute(
    "select token from access_tokens where user_id=? and valid_until_ms is null "
    "order by last_validated desc, id desc limit 1",
    (user,),
).fetchone()
if not token:
    sys.exit("no valid access token for %s (log in once with that account)" % user)
headers = {"Authorization": "Bearer " + token[0], "Content-Type": "application/json"}
q_room = urllib.parse.quote(room)
req = urllib.request.Request(
    "%s/rooms/%s/send/m.room.message/%s" % (base, q_room, uuid.uuid4().hex),
    data=json.dumps({"msgtype": "m.text", "body": body}).encode(),
    method="PUT",
    headers=headers,
)
event_id = json.loads(urllib.request.urlopen(req, timeout=20).read())["event_id"]
print("sent as", user, "event", event_id)
check = urllib.request.Request("%s/rooms/%s/event/%s" % (base, q_room, urllib.parse.quote(event_id)), headers=headers)
got = json.loads(urllib.request.urlopen(check, timeout=20).read())
print("verified:", got["content"]["body"] == body)
