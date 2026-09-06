# angelone-market-service

One AngelOne SmartAPI session (yours). Fans live prices and cached market data
out to as many consumers as you want — your `trading` backend, your website,
anything. **No visitor to your site ever logs into AngelOne.** No per-user
broker credentials exist anywhere in this codebase. It never places an order,
never touches funds — it only reads market data.

Verified and working as of this build: real login, real live feed, real
dedup — see `API_TESTING.md` for the actual proof, not just claims.

Runs on **port 8081**. Your `trading` backend runs separately on 8080.

---

## The one-sentence version of why this exists

Instead of every website visitor needing their own AngelOne account just to
see a stock chart, this service logs in **once**, watches whatever symbols
anyone currently cares about, and re-broadcasts that data to everyone —
proven in testing: two independent consumers watching the same stock produced
exactly **one** upstream AngelOne subscription and **one** shared tick feed,
not two.

---

## Project Structure

```
src/main/java/com/angelone/angelone_market_service/
├── AngeloneMarketServiceApplication.java   Spring Boot entrypoint
│
├── session/            ← the ONE shared AngelOne login, nothing else touches auth
│   ├── AngelSessionManager.java    Holds the current jwt/refresh/feed tokens for the
│   │                               whole app; scheduled token refresh + daily relogin.
│   ├── StartupLoginRunner.java     Logs in once at boot, before anything else runs.
│   ├── TotpGenerator.java          Generates a live 6-digit TOTP from ANGEL_TOTP_SECRET.
│   ├── AngelAuthDtos.java          Request/response records for AngelOne's auth endpoints.
│   └── SessionController.java      GET /internal/session/status, POST /internal/session/relogin
│
├── client/             ← talking to AngelOne's REST API
│   ├── AngelHeaders.java           Builds the 7 required SmartAPI headers in one place
│   │                               (X-PrivateKey, X-ClientLocalIP, X-MACAddress, etc.)
│   └── AngelRestCaller.java        Thin RestClient wrapper; retries once on a 401 after
│                                   forcing AngelSessionManager to refresh.
│
├── marketdata/         ← YOUR read-only market-data API (this is what `trading` calls)
│   ├── MarketDataController.java   GET /market/quote, /market/candles, /market/greeks,
│   │                               /market/oi, /market/intraday-eligible, /market/cautionary;
│   │                               POST /market/brokerage, /market/margin (calculators —
│   │                               POST because the body is a list of orders/positions)
│   ├── MarketDataService.java      Cache-check → call AngelOne → cache-store, per endpoint
│   └── MarketDataDtos.java         Request/response records for the above
│
├── feed/               ← the live WebSocket connection to AngelOne (binary ticks)
│   ├── AngelFeedClient.java        The only thing that opens a socket to AngelOne itself
│   ├── SubscriptionManager.java    Reference-counts subscribers per symbol/mode so
│   │                               AngelOne only ever sees ONE subscribe per unique key
│   ├── TickParser.java             Decodes AngelOne's little-endian binary tick payload
│   ├── Tick.java                   Parsed tick model
│   └── TickListener.java           Callback interface for consumers of parsed ticks
│
├── broadcast/          ← YOUR public-facing WebSocket (what `trading`/browsers connect to)
│   ├── PublicFeedWebSocketHandler.java   Handles /ws/feed connections; holds no AngelOne
│   │                                     credentials, knows nothing about TOTP or JWTs
│   ├── WebSocketConfig.java              Registers /ws/feed + allowed CORS origins
│   └── FeedMessages.java                 JSON shapes sent to your own /ws/feed clients
│
├── config/
│   ├── AngelProperties.java        Binds `angel.*` (credentials, IPs, MAC, cron, refresh)
│   ├── CacheProperties.java        Binds `cache.*` (TTLs per cache, per candle interval)
│   ├── InternalProperties.java     Binds `internal.api-key`
│   ├── CacheConfig.java            Caffeine cache beans (quote/candle/greeks), variable
│   │                               per-interval TTL for candles
│   ├── InternalApiKeyFilter.java   Enforces X-Internal-Api-Key on everything except /ws/feed
│   └── AppConfig.java              General bean wiring (RestClient, etc.)
│
└── exception/
    └── GlobalExceptionHandler.java Translates AngelOne upstream errors (e.g. a 400) into
                                     consistent HTTP responses (e.g. 502 Bad Gateway)
```

**Rule of thumb for where new code goes:** a new *calculator* or *read-only market
data* endpoint = one new method in `marketdata/` (controller + service + DTO), calling
out through the existing `AngelRestCaller`. It should never need to touch `session/` or
`feed/` — those are already-solved problems. See **Roadmap** below — nothing's queued
up right now, but this is the pattern the next endpoint should follow.

---

## Roadmap — what's built vs. what's left

**Nothing is queued up anymore — every item that was ever on this roadmap is built.**
Covered in `API_TESTING.md`: session status/relogin, the full `/instruments/*` lookup
family, `/market/quote`, `/market/candles`, `/market/greeks`, `/market/oi`,
`/market/intraday-eligible`, `/market/cautionary`, `/market/brokerage`,
`/market/margin`, and the `/ws/feed` WebSocket (subscribe/unsubscribe, dedup, fan-out).

| Group | SmartAPI source endpoint | Built as | Status |
|---|---|---|---|
| Instrument lookup | `POST .../order/v1/searchScrip`-equivalent | `GET /instruments/resolve`, `/instruments/token`, `/instruments/search` — symbol⇄token, backed by the daily scrip-master dump | ✅ built |
| Instrument lookup | `GET OpenAPIScripMaster.json` | Daily-cached instrument master (backs the three lookups above) — see [Data & Cache Policy](#data--cache-policy--what-gets-committed-what-gets-regenerated) below for how it's kept fresh | ✅ built |
| Calculators | `POST .../brokerage/v1/estimateCharges` | `POST /market/brokerage` — estimate charges/taxes for a hypothetical order (or basket) | ✅ built |
| Calculators | `POST .../margin/v1/batch` | `POST /market/margin` — real-time margin required for a basket of hypothetical positions | ✅ built |
| Historical | `POST .../historical/v1/getOIData` | `GET /market/oi` — historical open interest, sibling of `/market/candles`, same interval constants and symbol/token resolution | ✅ built |
| Reference data | `GET .../marketData/v1/nseIntraday`, `.../bseIntraday` | `GET /market/intraday-eligible?exchange=NSE\|BSE` — which scrips allow intraday + their margin multiplier | ✅ built |
| Reference data | `GET .../securities/v1/cautionaryScrips` | `GET /market/cautionary` — ASM/GSM caution-flagged scrips | ✅ built |

**Not yet live-exercised** (built and reviewed, same honest caveat as everything else in
this doc — see `API_TESTING.md`'s status table for the current per-endpoint testing state):
`/market/oi`, `/market/intraday-eligible`, `/market/cautionary`.

**Intentionally excluded, not on the roadmap:** placing/modifying/cancelling orders,
GTT rules, portfolio holdings/positions, funds & margins (RMS), and logout. All of these
either move real money/orders on the one shared account or genuinely belong to a
different, per-user AngelOne login flow (`publisher-login`) — out of scope for a
public read-only market-data fan-out service.

---

## Part 1 — Get your 4 AngelOne credentials

You need an existing AngelOne trading account first. Then collect:

### Client Code & PIN
Your AngelOne login ID, and your 4-digit trading PIN (not your login
password — the numeric PIN used to confirm orders).

### API Key
1. Sign up separately at `smartapi.angelone.in` (a developer account, not
   your trading login).
2. **My Profile → My API → Create an App** → type **Trading APIs**.
3. Fill the form:
   - **App Name** — anything.
   - **Redirect URL** — AngelOne **rejects** `localhost`/`127.0.0.1` outright
     ("Localhost is not allowed"). Use any real-looking domain instead, e.g.
     `https://example.com`. It's never actually called — this service logs in
     directly (`loginByPassword`), not through AngelOne's browser-redirect
     flow — so the value only needs to satisfy the form, nothing more.
   - **Primary Static IP** — required (SEBI compliance rule, added 2026).
     Get yours with `curl ifconfig.me` and use the real result. It only needs
     to be a well-formed IPv4 address for this project's scope — actual
     enforcement only applies to order placement/GTT calls, which this
     service never makes. Still use your real IP rather than inventing one;
     costs nothing and avoids future surprises.
   - Everything else optional, leave blank.
4. Submit → reveal and copy the **API Key** → this is `ANGEL_API_KEY`.

### TOTP Secret
1. `smartapi.angelone.in/enable-totp` → log in, verify OTP.
2. You'll see a QR code **and** a 32-character text string next to it. **Copy
   the text, not the QR.** This is `ANGEL_TOTP_SECRET`.
3. Never paste a live 6-digit code here — the service generates its own fresh
   code from this secret on every login (`TotpGenerator`).

If that page ever says it can't detect your machine IP, it's almost always a
browser privacy setting (WebRTC blocked, strict tracking protection, an
ad-blocker) — try a clean browser profile with extensions off before assuming
it's an AngelOne-side bug.

---

## Part 2 — Configure

```bash
cp .env.example .env
```

```bash
ANGEL_CLIENT_CODE=your_actual_client_code
ANGEL_PIN=your_4_digit_pin
ANGEL_TOTP_SECRET=the_32_char_secret
ANGEL_API_KEY=your_api_key
ANGEL_LOCAL_IP=127.0.0.1
ANGEL_PUBLIC_IP=your_real_public_ip
ANGEL_MAC_ADDRESS=AA:BB:CC:DD:EE:FF
INTERNAL_API_KEY=generate-with-openssl-rand-hex-32
```

`INTERNAL_API_KEY` isn't from AngelOne — you invent it. It's the shared
secret your `trading` backend sends to prove it's allowed to call this
service.

Config lives in **`application.yml`** (not `.properties` — if Spring
Initializr gave you an empty `application.properties`, delete it; having both
present at once is a silent-override trap).

---

## Part 3 — Run it

```bash
java -version   # need 21+
mvn -version
```

### Docker (recommended — handles `.env` for you automatically)
```bash
docker compose up -d --build
docker compose logs -f
```

### Maven directly
```bash
./mvnw spring-boot:run
```

**The one thing that trips people up every time:** plain `mvn`/`./mvnw`
does **not** read `.env` on its own — only `docker compose` does. If you run
via Maven, get the vars into the process yourself:

```bash
# bash/zsh
export $(grep -v '^#' .env | xargs) && ./mvnw spring-boot:run

# fish
env (grep -v '^#' .env | grep -v '^$') ./mvnw spring-boot:run
```

**How to tell if you forgot this:** the startup log will show
`Logging into AngelOne as client ${ANGEL_CLIENT_CODE}` — a literal unresolved
placeholder instead of your real client code — followed by an
`UnknownContentTypeException`. That exception is always a symptom of this one
cause, not a separate bug.

### What a clean startup actually looks like
```
AngelOne login succeeded
AngelOne feed WebSocket opened
Connected to AngelOne feed stream
```
All three lines, every time, or something upstream is wrong.

---

## Part 4 — Verify

Full step-by-step with expected responses (curl **and** Postman) lives in
`API_TESTING.md`. Fastest smoke test:

```bash
curl localhost:8081/internal/session/status -H "X-Internal-Api-Key: $INTERNAL_KEY"
# {"loggedIn": true}

curl "localhost:8081/market/quote?mode=LTP&exchange=NSE&tokens=3045" \
  -H "X-Internal-Api-Key: $INTERNAL_KEY"
# real SBIN-EQ price
```

### Error reference

| Error | Cause |
|---|---|
| `AB1000` Invalid Email Or Password | `ANGEL_PIN` wrong — it's the trading PIN, not your account password |
| `AB1002` Invalid Password Length | PIN isn't 4 digits |
| TOTP login failure | `ANGEL_TOTP_SECRET` wrong — usually the live 6-digit code got pasted instead of the 32-char secret |
| `AG8001`/`AG8002` Invalid/Expired Token | Session JWT is stale — `AngelRestCaller` should self-heal via one retry after a forced refresh; if it doesn't, check refresh logs |
| `AB1009`/`AB1018` Symbol Not Found | Wrong/garbage token in a quote or candle request |
| `400 BAD_REQUEST` from `GlobalExceptionHandler` on `/market/candles` | Almost always a date typo — format is `yyyy-MM-dd hh:mm`, month/day swapped is the most common mistake |
| `"No Data Available"` (`AB9019`) on `/market/greeks` | Options Greeks are computed live and need an active market session — this call will return nothing outside NSE trading hours (9:15 AM–3:30 PM IST, Mon–Fri), that's expected, not a bug |
| `UnknownContentTypeException` + literal `${ANGEL_CLIENT_CODE}` in logs | `.env` never reached the process — see Part 3 |
| `package com.fasterxml.jackson.databind does not exist` at compile time | Spring Boot 4's `spring-boot-starter-webmvc` no longer pulls in Jackson automatically — already fixed in this project's `pom.xml` via an explicit `jackson-databind` dependency; if you regenerated the project from Spring Initializr yourself, you'll need to re-add it |

---

## How `trading` integrates with this service

Nothing in `trading` needs AngelOne credentials or binary parsing — it just
calls this like any other internal dependency.

**REST** (cached — safe to call often):
```
GET http://localhost:8081/market/quote?mode=FULL&exchange=NSE&tokens=3045,881
GET http://localhost:8081/market/candles?exchange=NSE&symbolToken=3045&interval=ONE_DAY&fromDate=2026-08-01 09:15&toDate=2026-08-14 15:30
GET http://localhost:8081/market/greeks?name=TCS&expiryDate=25AUG2026
GET http://localhost:8081/market/oi?exchange=NFO&symbolToken=46823&interval=THREE_MINUTE&fromDate=2026-08-06 11:15&toDate=2026-08-06 12:00
GET http://localhost:8081/market/intraday-eligible?exchange=NSE
GET http://localhost:8081/market/cautionary
```
Every request needs `X-Internal-Api-Key: <your INTERNAL_API_KEY>`. Response
shape mirrors AngelOne's own envelope: `{status, message, errorcode, data}`.
`/market/quote`, `/market/candles`, `/market/oi`, `/market/brokerage`, and
`/market/margin` all also accept a human-readable `symbol`/`symbols` in place of a
raw token — see `API_TESTING.md` section 4 for how symbol resolution works.

**WebSocket** — connect to `ws://localhost:8081/ws/feed` (intentionally
unauthenticated — see `InternalApiKeyFilter`; lock this down before it's
reachable outside your own network). Send:
```json
{"action": "subscribe", "exchangeType": 1, "token": "3045", "mode": 1}
```
`exchangeType`: 1=NSE, 2=NFO, 3=BSE, 4=BFO, 5=MCX. `mode`: 1=LTP, 2=Quote,
3=SnapQuote. You'll get ticks back shaped like:
```json
{"type": "tick", "tick": {"subscriptionMode": 1, "exchangeType": 1, "token": "3045", "ltp": 568.2, ...}}
```
Same shape with `"action": "unsubscribe"` to stop, or just disconnect —
cleanup is automatic either way.

**Recommended pattern:** don't have every browser open its own `/ws/feed`
connection directly. Have `trading` maintain ONE connection here per set of
symbols any of its users are watching, then fan that out over `trading`'s
*own* WebSocket to browsers — same dedup pattern, one layer further out. This
also lets `trading` enforce your site's own auth/rate-limits on who gets to
watch what, which this service deliberately doesn't do — it trusts its one caller.

---

## What's actually been verified vs. what's still unverified

**Confirmed working, with real evidence, not just code review:**
- Login, token refresh, and the scheduled daily re-login cycle
- Live WebSocket connection to AngelOne + auto-reconnect
- `/market/quote` and `/market/candles` returning real data
- **Subscription dedup**: two independent consumers subscribing to the same
  symbol produced exactly one upstream AngelOne subscription (proven by the
  absence of a duplicate "First watcher" log line, and by "Replaying **1**
  subscription group" after a reconnect despite two active watchers)
- **Fan-out**: a single AngelOne tick delivered to both consumers
  simultaneously (proven by identical `exchangeTimestamp`/`ltp` arriving at
  both WebSocket connections one millisecond apart)

**Not yet exercised:**
- `/market/greeks` — code path is right, but every test attempt so far
  landed outside market hours
- `/market/oi`, `/market/intraday-eligible`, `/market/cautionary` — newly built,
  code-reviewed against AngelOne's documented shapes, not yet run against a live
  session. Same caveat as everything the instrument-resolution work carried when it
  first shipped: "should work" isn't "observed working" — run these yourself against
  your own account before trusting them in production, per `API_TESTING.md`.

**Not yet built at all:**
- Anything in `trading` that actually calls this service — that integration
  doesn't exist yet, this service has only been tested standalone
- Nothing else remains on the **[Roadmap](#roadmap--whats-built-vs-whats-left)**
  above — see it for what's intentionally excluded and why.

---

## Data & Cache Policy — what gets committed, what gets regenerated

This comes up the moment you actually run the service: `instrument.cache-file-path`
(default `./data/instruments-cache.json`) fills up with AngelOne's entire scrip master —
tens of thousands of rows, tens of megabytes. **Don't commit it, and don't push it.**
Treat it exactly like a `node_modules` folder or a `target/` build output: generated
data, not source.

**Why it's safe to never commit:**
- It's regenerated automatically. `StartupInstrumentLoader` tries disk first (fast,
  works offline), then always attempts a live fetch from AngelOne on every boot — so a
  brand-new checkout with an empty `data/` directory self-populates within seconds of
  `docker compose up`, no manual step required.
- It's a **full replace, never a merge**, every time it refreshes (see
  `InstrumentIndex.build` / `InstrumentService.refreshFromAngelOne`) — AngelOne only
  ever publishes the complete current dump, never a diff, so there's no meaningful
  "history" of this file worth version-controlling. Yesterday's row for a delisted
  scrip isn't something you'd ever want to diff back in.
- It changes daily and is large — the two classic reasons a file doesn't belong in git
  (repo bloat, and permanent merge-conflict bait on every commit).

**What the actual freshness system is** (already wired up, this is how to reason about it):

1. **On every boot:** load whatever's on disk (if anything) → immediately attempt a live
   fetch. Live always wins if it succeeds; disk is purely the "don't start completely
   empty" fallback.
2. **Once a day** (`instrument.refresh-cron`, default 08:30 IST, just ahead of NSE
   pre-open): scheduled full re-fetch → rebuild the whole in-memory index → atomically
   swap it in → persist the new snapshot to disk. This is the same path as step 1's live
   fetch, just cron-triggered instead of boot-triggered.
3. **On any failure** (AngelOne unreachable, bad JSON): the previous good snapshot keeps
   serving, in memory and on disk — a failed refresh degrades to "slightly stale" and
   never to "empty" or "half-updated."
4. **`GET /instruments/status` tells you which state you're in** — `source: "live"` (a
   real fetch succeeded), `"disk"` (only the on-disk copy loaded so far), or `"none"`
   (nothing yet). As of this build it also returns `stale: true/false`: `true` once the
   currently-loaded snapshot is older than `instrument.stale-after-hours` (default
   **30 hours** — one full missed daily cycle plus a buffer, so a single slow morning
   doesn't false-positive the moment the clock passes 24h). `stale: true` is your signal
   to look into it — check logs, or just force it yourself:
   ```bash
   curl -s -X POST localhost:8081/internal/instruments/refresh -H "X-Internal-Api-Key: $INTERNAL_KEY"
   ```

**What this means for you day to day:**
- `data/` should be in `.gitignore` (see the one included in this repo). If you're
  deploying via Docker, mount `data/` as a volume so restarts don't throw away a good
  disk-cache copy unnecessarily — but treat that volume as disposable, not as backup.
- If you fork/clone this repo, you never need to seed `instruments-cache.json`
  yourself — first boot handles it, assuming AngelOne's dump endpoint is reachable from
  wherever you're running.
- The same "full replace, never commit the generated artifact" logic applies to any
  future daily-regenerated dataset you add here — don't design a new one to be
  hand-edited or diffed; design it to be thrown away and rebuilt, same as this one.
