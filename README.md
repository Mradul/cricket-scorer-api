# cricket-scorer-api

Full lifecycle cricket scoring backend — pre-match setup (`SETUP → LIVE`) + live ball-by-ball innings with undo, optimistic concurrency, and file-backed persistence.

Extracted and rebuilt from the `cric-scorer-setup` oracle into a standalone public API. Java 21 + Quarkus + H2 file DB.

> **Original baseline:** `cricket-scorer-baseline` at `~/cricket-scorer-baseline` (private). This repo is the cleaned public publish.

## Stack

- Java 21 (Temurin 21+)
- Quarkus 3.15.3 (`quarkus-rest-jackson`, `quarkus-smallrye-health`, `quarkus-agroal`, `quarkus-jdbc-h2`)
- H2 file-backed DB at `jdbc:h2:file:/tmp/cricket;DB_CLOSE_ON_EXIT=FALSE` — survives JVM kill + restart (authoritative store)
- Maven wrapper `./mvnw`

## Quick start

```bash
# build
./mvnw -q package -DskipTests

# run (listens on http://localhost:8080, health at /q/health)
java -jar target/quarkus-app/quarkus-run.jar
```

Requires Java 21+ and port 8080 free. Persistence file at `/tmp/cricket.mv.db`.

## API — 15 operations

### Match setup (8 endpoints)

#### `POST /matches` — create match
```json
{ "id": "optional, else UUID", "teamA": {"id":"required","name":"optional"}, "teamB": {"id":"required != teamA.id"} }
```
201 with `MatchState`, 400 validation, 409 duplicate id.

#### `PUT /matches/{id}/squads/{teamId}` — register squad
```json
{ "players": [ {"id":"required","name":"..."} ] }
```
Clears XI + roles for that side on re-register. 200, 400, 404, 409 if not SETUP.

#### `PUT /matches/{id}/playing-xi/{teamId}` — select XI
```json
{ "playerIds": ["exactly 11, distinct, subset of squad, order preserved"] }
```
200, invalidates roles.

#### `PUT /matches/{id}/roles/{teamId}` — assign captain + keeper
```json
{ "captainId": "in XI", "wicketkeeperId": "in XI" }
```
XI must exist else 409.

#### `POST /matches/{id}/toss` — record toss
```json
{ "winnerTeamId": "teamA|teamB", "decision": "BAT|BOWL" }
```
Derives `battingTeamId` / `bowlingTeamId`. Single-record, second call 409.

#### `PUT /matches/{id}/rules` — set rules
```json
{ "totalOvers":20, "powerplayOvers":6, "wideRuns":1, "noBallRuns":1, "maxOversPerBowler":4 }
```
Bounds: `totalOvers>0`, `0<=powerplay<=total`, `wideRuns>=1`, `noBallRuns>=1`, `1<=max<=total`.

#### `POST /matches/{id}/innings/start` — SETUP → LIVE
```json
{ "strikerId":"...", "nonStrikerId":"... != striker", "openingBowlerId":"..." }
```
Guard `setupComplete`: toss+rules+batting/bowling derived, each XI 11 subset squad, each roles captain+keeper in XI. 400 outsider, 409 incomplete/second start.

#### `GET /matches/{id}`

### Live innings (7 endpoints)

#### `POST /innings` — init LIVE innings
```json
{
  "id":"optional else UUID",
  "battingLineup":["11 distinct"], "bowlingLineup":["≥1 distinct"],
  "strikerId":"in batting", "nonStrikerId":"in batting != striker",
  "openingBowlerId":"in bowling",
  "totalOvers":">0", "maxOversPerBowler":">0 && <=total", "wideRuns":">=1", "noBallRuns":">=1",
  "target":"optional >0"
}
```
201 with `InningsState` (`version=0`, `currentOverNumber=1`, `batterCards`/`bowlerCards` ordered = lineup order, `extras` ordered `[WIDE,NO_BALL,BYE,LEG_BYE,PENALTY]`, nulls serialized).

#### `POST /innings/{id}/deliveries` — score delivery
```json
{ "runsOffBat":0, "extra": {"type":"WIDE|NO_BALL|BYE|LEG_BYE|PENALTY", "runs":1} | optional }
```
Legal = not WIDE/NO_BALL. Validates `runsOffBat>=0`, extra below configured threshold 400, BYE/WIDE/LEG_BYE requires `runsOffBat==0`. Scores batter/bowler/extras/total, strike rotation odd `runsAdded=runsOffBat+extraRuns`, double swap on over boundary (6 legal). Closure: `TARGET_REACHED` if `total>=target`, `ALL_OUT` if `wickets>=lineup-1`, `OVERS_EXHAUSTED` if `legalBalls>=totalOvers*6`, else over-end → `awaitingBowler=true, currentBowlerId=null, lastOverBowlerId=prev, currentOverLegalBalls=0, currentOverNumber++`. 409 if COMPLETE or awaiting bowler.

#### `POST /innings/{id}/wickets` — delivery with wicket
```json
{
  "dismissalType":"BOWLED|CAUGHT|LBW|RUN_OUT|STUMPED|HIT_WICKET",
  "batterOutId":"at crease",
  "incomingBatterId":"required unless all-out",
  "crossed":false, "runsOffBat":0, "extra":{...}
}
```
Only `RUN_OUT` may dismiss non-striker and is allowed on `NO_BALL`. Non-RUN_OUT credits bowler wicket. `crossed` controls post-wicket striker assignment.

#### `POST /innings/{id}/bowler` — assign new bowler
```json
{ "bowlerId":"in lineup" }
```
Must be awaiting else 409, not same as `lastOverBowlerId` 400, not over cap `legalBalls>=max*6` 400.

#### `POST /innings/{id}/close` — declare
No body. LIVE→COMPLETE `DECLARED`.

#### `POST /innings/{id}/undo` — atomic undo last delivery
No body required (`{}` accepted). Allowed in both LIVE and COMPLETE — only way to reopen. Reverts exactly one delivery: `totalRuns, legalBalls, wickets, extras, batterCards, bowlerCards, deliveries (pop), fallOfWickets, strikerId/nonStrikerId (odd + over double-swap), currentOverLegalBalls/currentOverNumber/currentBowlerId/lastOverBowlerId/awaitingBowler, status= LIVE, completionReason=null`. Durably persisted. 409 if no deliveries. Versioned.

#### `GET /innings/{id}`

### Concurrency

`If-Match-Version` header optional on `deliveries|wickets|bowler|close|undo`:
- absent → existing guards unchanged
- malformed/negative → 400 `{"error":...}`
- stale/future ≠ current `version` → 409
- success → `version++` (exactly one), persisted, failed requests don't bump
- concurrent same version → exactly one 200, one 409

All 400/404/409 bodies are JSON `{"error":"<message>"}` with `Content-Type: application/json`, including framework-level invalid enums.

## Examples

```bash
# create match
curl -s -X POST http://localhost:8080/matches -H 'Content-Type: application/json' \
  -d '{"teamA":{"id":"IND","name":"India"},"teamB":{"id":"AUS"}}' | jq

# register squads
curl -s -X PUT http://localhost:8080/matches/<id>/squads/IND -H 'Content-Type: application/json' \
  -d '{"players":[{"id":"p1","name":"Rohit"}, ...]}' | jq

# init innings
curl -s -X POST http://localhost:8080/innings -H 'Content-Type: application/json' \
  -d '{"battingLineup":["b1","b2","b3","b4","b5","b6","b7","b8","b9","b10","b11"],"bowlingLineup":["o1","o2"],"strikerId":"b1","nonStrikerId":"b2","openingBowlerId":"o1","totalOvers":20,"maxOversPerBowler":4,"wideRuns":1,"noBallRuns":1}' | jq

# delivery
curl -s -X POST http://localhost:8080/innings/<id>/deliveries -H 'Content-Type: application/json' -d '{"runsOffBat":4}' | jq
```

## Persistence & restart check

```bash
java -jar target/quarkus-app/quarkus-run.jar & pid=$!
curl http://localhost:8080/q/health
kill $pid; sleep 1
java -jar target/quarkus-app/quarkus-run.jar &
curl http://localhost:8080/matches/<id> # still there
curl http://localhost:8080/innings/<id>
```

## Tests

Original grader (19 pytest groups) expected to pass if run against this build:

```
# from ~/mraduljain-long-horizon/cric-scorer-setup
pytest tests/test_api.py -v
```

Build artifact `target/quarkus-app/quarkus-run.jar` is the contract.

## License

MIT — see LICENSE.
```

