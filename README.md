# Vote in Minecraft
Minecraft 26.3 Fabric mod with a custom voting lectern, plus Node.js web API and public results dashboard.

## Architecture
- `site/data/ballots.json`: editable JSON source of ballot titles, question text, options and opening/closing dates.
- `site/src/server.js`: built-in Node.js HTTP server and authenticated API.
- `site/src/core.js`: validation, serialized atomic vote storage, pseudonymous identity, tallies.
- `site/public/`: responsive results dashboard refreshing every 15 seconds.
- `mod/`: Minecraft Fabric 26.3 / Java 25 mod, custom block model with a white written card.
- The Minecraft server fetches ballots via GET. Players click options in in-game chat and the server sends signed-in account UUID/nickname via authenticated POST. The secret is never sent to players.

## 1. Start web API
```bash
cd site
cp .env.example .env
# Replace MC_API_KEY and VOTER_HASH_SECRET with TWO different random secrets, >=24 characters
set -a; . ./.env; set +a
npm start
```
Open the website on localhost, port 3000 by default. Node.js 20+ required. No npm dependencies.

To change content, edit `site/data/ballots.json`. For `type: "vote"`, configure 2-8 `options` with unique `id` and `label`. For `type: "petition"`, use `options: []`. Setting `publishSignatures: true` publicly shows players' Minecraft nicknames, with explicit on-screen warning before they agree. This is not a legally valid electronic signature.

`enabled: false` closes a ballot. `startsAt` and `endsAt` accept UTC ISO8601 values. Do not change option IDs after accepting votes.

## 2. Build Minecraft Fabric mod
Install Java 25, Gradle 9.8.1, Minecraft 26.3 / Fabric Loader 0.20.1 and compatible Fabric API. Build:
```bash
cd mod
gradle build
```
Install the built JAR on the **server and clients**, with Fabric API. After first server start edit `config/vote-in-minecraft.json`:
```json
{
  "apiBaseUrl": "https://votes.example.org",
  "apiKey": "SAME_SECRET_AS_MC_API_KEY",
  "serverId": "default",
  "timeoutSeconds": 6
}
```
Local testing can use `http://127.0.0.1:3000`. Other hosts must use HTTPS. Use `online-mode=true` on the Minecraft server for verified account UUIDs.

```mcfunction
/give @s vote_in_minecraft:ballot_lectern
/ballot
/ballot show town-hall
/ballot vote town-hall library
/ballot sign save-our-village
```

Right-click the placed lectern with an empty hand. It displays the active voting cards and clickable action controls **in Minecraft chat**. A custom full-screen GUI is not included in this first iteration.

## API endpoints
| Method | Route | Authentication |
|---|---|---|
| GET | /api/v1/health | Public |
| GET | /api/v1/ballots | Public |
| GET | /api/v1/ballots/active | Public |
| GET | /api/v1/ballots/:id/results | Public |
| POST | /api/v1/ballots/:id/submissions | Bearer token and X-Minecraft-Server |

Vote POST example:
```json
{"playerUuid":"d0b2ad4c-6a0a-4f8a-82cf-febcbb0cc4e9","playerName":"Builder123","optionId":"library","serverId":"default"}
```
For petitions omit `optionId` and set `consentToPublishName: true` to allow the publicly displayed nickname.

## Tests
```bash
cd site && npm test
```
No database setup required. Submissions are in `site/data/submissions.json` (gitignored), atomically replaced. Back them up. For high traffic or multi-instance server deployments migrate to SQLite or Postgres with a UNIQUE constraint on voter+ballot. The API authenticates a trusted Minecraft server, not individual humans. The system is for community polls, **not election-grade or legally binding voting**.

The backend was tested locally. Minecraft compilation and block rendering still require CI and in-game validation.

## Privacy and operations
- UUIDs are stored only as HMAC digests using `VOTER_HASH_SECRET`.
- Ordinary vote nicknames are never saved. Names on petitions are saved only with explicit consent when `publishSignatures` is enabled.
- Keep both secrets private, serve via HTTPS, apply secure file permissions and set a retention policy for personal data.
- A trusted Minecraft server can still fabricate submissions; protect its credentials and require online-mode authentication.

Licensed under MIT; see LICENSE.
