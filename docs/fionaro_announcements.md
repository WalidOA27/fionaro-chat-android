# Fionaro release announcements

How to announce a new Fionaro Chat version on the announcement board.

<!--- TOC -->

* [Where](#where)
* [Message format](#message-format)
* [Publishing](#publishing)
  * [How it works](#how-it-works)
  * [Troubleshooting](#troubleshooting)

<!--- END -->

## Where

The board is the Matrix room **Anuncios Fionaro** (`!cBndmQDEDywPmRQzqI:fionaro.pw`). It is not encrypted. Only
`@admin:fionaro.pw` (power level 100) can post. Users read it in Fionaro Chat.

## Message format

Messages are written in Spanish, short, neutral and professional. Copy the previous file in
[`tools/fionaro/announcements/`](../tools/fionaro/announcements) and name it after the version (`1.3.0.txt`):

- Title line: `📢 Fionaro Chat <version>: actualización disponible`.
- One sentence asking people to update.
- `Novedades`: one bullet (`•`) per user-visible change, `Name: what it means for the user.`
- Closing line: `Si encuentras algún problema, escríbenos por aquí.`

Do not explain how to update (users get updates through Obtainium) and do not mention internal changes (icons, privacy
settings, CI, refactors). Get the owner's approval of the text before publishing.

## Publishing

Publish the GitHub release first (tag `v<version>`, marked as latest), then:

```bash
# Dry run: checks the room (not encrypted, admin present) and prints the message.
tools/fionaro/announce.sh tools/fionaro/announcements/1.3.0.txt

# Publish as @admin:fionaro.pw and verify the event on the server.
tools/fionaro/announce.sh tools/fionaro/announcements/1.3.0.txt --send
```

The script asks for the root password of the homeserver host (or reads `FIONARO_ROOT_PASSWORD`). It never stores it.

### How it works

`announce.sh` connects with `ssh homelab` (key-based; see `~/.ssh/config`), copies
[`announce_remote.py`](../tools/fionaro/announce_remote.py) and the message to a temporary directory, and runs it as root
with `su`. The remote script:

1. Opens the Synapse SQLite database **read-only** (`~/comunicacion/matrix/synapse/config/homeserver.db`, mounted as
   `/data` in the `matrix_synapse` container).
2. Reads the room name, encryption state and power levels. It refuses to post to an encrypted room.
3. Takes the most recent valid access token of the room admin.
4. Sends an `m.text` message through the public client API (`https://matrix.fionaro.pw`; Synapse does not listen on the
   host's port 8008) and reads the event back to confirm the body matches.

Overrides: `FIONARO_SSH_HOST`, `FIONARO_ROOM_ID`, `FIONARO_SYNAPSE_DB`, `FIONARO_HOMESERVER_URL`.

### Troubleshooting

- `unable to open database file`: the database path changed; check `database.args.database` in `homeserver.yaml`.
- `no valid access token`: log in once with `@admin:fionaro.pw` (for example in Element Web) and retry.
- `Connection refused`: the homeserver URL is wrong or Synapse is down.
- A message sent by mistake can be removed by redacting it as the admin from Element Web.
