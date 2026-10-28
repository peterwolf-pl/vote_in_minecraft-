# Vote in Minecraft - Minecraft 26.3 / Fabric Server-Side

**No client mod is required.** Players connect using a vanilla Minecraft Java 26.3 client. The Fabric mod is installed only on the dedicated server. It uses the built-in Minecraft Dialogs API and vanilla lecterns.

## System

- `mod/`: Fabric 26.3 / Java 25 dedicated-server mod with native dialogs, API access and persistent voting lectern coordinates.
- `site/`: Node.js API, editable JSON ballots, persistent votes, public results and tests.
- `resourcepack/`: optional white-paper lectern model. Changes the visual model of **all** vanilla lecterns when enabled; it does not change voting rules.

## Start the website

```bash
cd site
cp .env.example .env
# Enter distinct strong MC_API_KEY and VOTER_HASH_SECRET values
set -a; . ./.env; set +a
npm start
```

Edit `site/data/ballots.json` to create votes (`type: vote`, options list) or petitions (`type: petition`, empty options). The mod fetches active ballots when a player opens a station. The site validates ballot IDs, options, closing dates and duplicate submissions. Use HTTPS in production. Keep credentials outside Git.

## Build server mod

```bash
cd mod
gradle build
```

Install the compiled mod JAR and Fabric API only in your dedicated server's `mods/` folder. **Do not install the mod on client machines.** Start once, set `apiBaseUrl`, `apiKey` and `serverId` in `config/vote-in-minecraft.json` and restart. Set `online-mode=true` in `server.properties` to use authenticated player UUIDs.

## Place a voting lectern

1. Place any ordinary `minecraft:lectern` and leave it empty.
2. As a server operator, look at it from at most 5 blocks.
3. Execute `/ballot station add`. The server stores dimension and position in `config/vote-stations.json`.
4. The player right-clicks the lectern: first a list of ballots, then a full native dialog with the text, options and confirmation.
5. To unmark a lectern, look at it and execute `/ballot station remove`.

Ordinary unregistered lecterns retain vanilla behavior. After destroying a registered lectern, its position remains registered. Remove the registration before reusing that position.

Commands for players: `/ballot`, `/ballot open`, `/ballot show <id>`, `/ballot vote <id> <option>`, `/ballot sign <id>`. The backend rechecks each submission even if the command was typed manually. Dialog confirmation is provided before voting or signing.

## Resource pack (optional)

Zip the contents of `resourcepack/` so `pack.mcmeta` is at the ZIP root. Upload that archive to a static hosting service and set `resource-pack` and `resource-pack-sha1` in `server.properties`. A player can vote without accepting it. **Visual limitation:** this sample pack replaces the base lectern model for all lecterns, not just voting stations. The server cannot add a new block model visible to a vanilla client without some client-provided assets.

## API

| Method | Endpoint | Purpose |
| --- | --- | --- |
| GET | `/api/v1/ballots/active` | Active vote and petition definitions |
| GET | `/api/v1/ballots/:id/results` | Public results |
| POST | `/api/v1/ballots/:id/submissions` | Server-authenticated submission |

Only Minecraft server sends the Bearer token and `X-Minecraft-Server` header; UUIDs are HMAC-pseudonymized by Node.js. Petition names are shown only with `publishSignatures: true` and explicit player confirmation. An in-game signature is not a qualified electronic signature.

## Test and limitations

`cd site && npm test` runs the Node tests. Fabric compilation and in-game verification require Java 25, Gradle/Fabric 26.3 and an actual dedicated server with a vanilla 26.3 client. This PR remains draft until the Java build and both vote/petition flows are verified.

The built-in Node data store is designed for a single process. For public high-volume deployment migrate votes to SQLite or PostgreSQL, and implement a retention/privacy policy. An online-mode UUID represents an authenticated game account, not a verified natural person.


## Signing the MKA public appeal from Minecraft

A new ballot id `mka-appeal` delegates signatures to the real MKA appeal database.
Configure the dedicated MKA Next.js endpoint (PR in `peterwolf-pl/obrona-mkal-pl`) first.
In `site/.env` set `MKA_APPEAL_API_BASE_URL` (HTTPS URL of the deployment that serves /apel),
`MKA_APPEAL_API_KEY` (32+ random characters, same as MKA `MINECRAFT_SIGNATURE_API_KEY`)
and `MKA_APPEAL_SERVER_ID` matching MKA `MINECRAFT_SIGNATURE_SERVER_ID`.

The Node.js service fetches the current published MKA appeal from
`GET /api/minecraft/signatures` and shows it in Minecraft's native dialogs.
A signed POST forwards only authenticated UUID, current appeal ID and explicit consent
to the MKA backend. The Minecraft mod knows only the Node service key.
MKA writes the signature with full_name=UUID, city=MINECRAFT and email=NULL.
The MKA public /apel counter automatically includes it. Re-signing returns 409.
Never forward client-controlled name/city/email to MKA.
If MKA is unreachable or unconfigured, this special petition is hidden from the
active list. Ordinary local ballots continue functioning.

The official MKA appeal uses a short-lived, player-bound confirmation token; typing `/ballot sign mka-appeal` opens its disclosure dialog instead of directly submitting. The backend must also verify explicit consent and the current appeal ID.
