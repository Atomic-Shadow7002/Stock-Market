# CODE_GUIDE.md — angelone-market-service, explained completely

This is the "explain literally everything" document. `README.md` tells you how to set
the project up and run it. `API_TESTING.md` tells you how to prove each endpoint works.
**This document tells you what every file does, why it exists, how every AngelOne
concept it depends on works, and what I found when I reviewed it.** Read this if you
come back in six months and need to remember why something is built the way it is.

> **How this was produced:** I read every `.java` file, `pom.xml`, `application.yml`,
> `.env.example`, and the AngelOne SmartAPI PDF you uploaded, line by line. I could
> **not** actually run `mvn compile` / `mvn test` in this environment — my sandbox has
> no network route to Maven Central (only npm/pypi/crates/GitHub-style hosts are
> reachable here), so a real build was impossible from my side. Everything below is a
> careful static read, cross-checked against the official AngelOne docs and, for the
> Spring Boot 4.1 dependency questions, against current Spring documentation via web
> search. Section 9 ("Issues Found") lists everything I'd want you to double-check by
> actually running `./mvnw clean compile` yourself.

---

## 0. What this service actually is, in one paragraph

`angelone-market-service` is a standalone Spring Boot app whose only job is: log into
**one** AngelOne SmartAPI account (yours), keep that one login alive forever, and
re-sell the market data it gets access to (quotes, candles, live ticks, greeks,
brokerage/margin calculators, instrument lookup) to as many internal callers as you
want — typically your `trading` backend, or a browser. It **never places an order**,
never touches money, and no end user ever needs their own AngelOne account. The whole
point is turning "1 AngelOne login" into "N consumers," safely, with one shared rate
limit and one shared WebSocket quota.

---

## 1. The AngelOne / SmartAPI concepts you need before any of the code makes sense

If you haven't touched this in a while, read this section first — every class in the
codebase assumes you know these terms.

### 1.1 Client Code, PIN, API Key, TOTP Secret
Four different secrets, easy to confuse:
- **Client Code** — your AngelOne account ID (like a username).
- **PIN** — your 4-digit trading PIN (not your web login password). Used, with the
  client code and a TOTP code, to log in via the API.
- **API Key** — issued by `smartapi.angelone.in` (a *separate* developer portal from
  your trading login) when you register an "app." Sent as the `X-PrivateKey` header on
  every single request.
- **TOTP Secret** — the 32-character Base32 string your authenticator app was seeded
  with when you turned on 2FA for SmartAPI. `TotpGenerator` uses this to compute a
  fresh 6-digit code on demand, so login can happen unattended (no human typing a code
  in every morning).

### 1.2 The three tokens you get back from a successful login
AngelOne's `loginByPassword` (and later `generateTokens`) responses hand back three
different tokens, each with a different job:
- **jwtToken** — the bearer token you attach to every authenticated REST call
  (`Authorization: Bearer <jwtToken>`).
- **refreshToken** — traded in later for a *new* jwtToken/feedToken pair without a full
  re-login (cheaper, and doesn't need a fresh TOTP code).
- **feedToken** — used only for the WebSocket market-data stream, not REST calls.

### 1.3 Session lifecycle rules (per AngelOne's docs)
- A session is **force-expired at midnight** regardless of activity — you must log in
  again fresh every day.
- The JWT itself has a shorter natural lifetime than that, so you're expected to
  proactively refresh it well before it'd expire on its own, rather than waiting for a
  401.
- `generateTokens` (refresh) is cheaper than `loginByPassword` (full login) because it
  doesn't need a new TOTP code — just the existing refresh token.

### 1.4 Envelope shape
Every SmartAPI REST response (success or failure) is the same JSON envelope:
```json
{ "status": true|false, "message": "...", "errorcode": "...", "data": { ... } }
```
This project mirrors that exact shape in its own responses (see `AngelEnvelope<T>`
below) so a caller who already knows how to parse AngelOne's format doesn't have to
learn a second one.

### 1.5 Exchange & subscription-mode codes (used constantly in the feed code)
| exchangeType (int) | Exchange |
|---|---|
| 1 | NSE cash market |
| 2 | NSE futures & options |
| 3 | BSE cash market |
| 4 | BSE futures & options |
| 5 | MCX (commodities) futures |

| mode (int) | Meaning |
|---|---|
| 1 | LTP — just the last traded price |
| 2 | Quote — LTP + open/high/low/close/volume |
| 3 | SnapQuote / "Full" — everything, including 5-level market depth |

### 1.6 WebSocket Streaming 2.0 — the binary tick protocol
This is the trickiest part of the whole SmartAPI surface, and the part
`feed/TickParser.java` exists to decode. Key facts from AngelOne's docs:
- URL: `wss://smartapisocket.angelone.in/smart-stream`
- Auth is via **headers on the WebSocket handshake** (`Authorization`, `x-api-key`,
  `x-client-code`, `x-feed-token`) — not a query string, unless you're a browser client
  (then it's `?clientCode=&feedToken=&apiKey=` in the URL, because browsers can't set
  arbitrary WebSocket headers).
- You must send the literal text frame `"ping"` **every 30 seconds** or AngelOne drops
  your connection. The server replies `"pong"`.
- Subscribe/unsubscribe requests are small JSON text frames; the **data AngelOne sends
  back is binary**, little-endian, with a fixed layout that depends on which mode you
  subscribed with. Packet size tells you which mode came back:
  - **51 bytes** → LTP mode
  - **123 bytes** → Quote mode
  - **379 bytes** → SnapQuote/Full mode (includes 5-level market depth — this project
    does not currently parse that far; see §9)
- Quota: **1000 total (token, mode) subscriptions** per session, shared across
  everyone using it. Up to **3 concurrent WebSocket connections** are allowed per
  client code, but this project intentionally uses exactly **one**, forever (see
  `AngelFeedClient`) — that's the entire reason `SubscriptionManager` exists.

### 1.7 Rate limits are per-account, not per-caller
AngelOne enforces limits like "10 requests/second" or "3 requests/second" **per
AngelOne client code** — it has no idea your one account is secretly serving 500
website visitors. If your service didn't dedupe/cache, 500 visitors loading the same
chart would trip AngelOne's rate limiter instantly. This is the entire reason the
Caffeine caches in `config/CacheConfig.java` exist.

---

## 2. Big-picture architecture

```
                     ┌─────────────────────────────────────────────┐
                     │            angelone-market-service           │
                     │                (port 8081)                   │
                     │                                                │
  AngelOne SmartAPI  │  session/        one shared login + tokens    │   your `trading`
  REST + WebSocket  ◄┼──client/         REST call wrapper + headers  │◄──  backend, or
  (their servers)    │  marketdata/     quote/candle/greeks/etc.     │    a browser
                     │  instrument/     symbol ⇄ token lookup        │  (server-to-server
                     │  feed/           the ONE upstream WS conn.    │   via X-Internal-
                     │  broadcast/      YOUR public WS, fans out     │   Api-Key, except
                     │  config/         wiring, caches, filters      │   /ws/feed which
                     │  exception/      turns errors into HTTP codes │   is open)
                     └─────────────────────────────────────────────┘
```

Two completely separate WebSocket relationships exist and it's easy to conflate them:
1. **`feed/AngelFeedClient`** — this service acting as a *client*, connecting *out* to
   AngelOne's `wss://smartapisocket.angelone.in/smart-stream`. Exactly one such
   connection ever exists.
2. **`broadcast/PublicFeedWebSocketHandler`** — this service acting as a *server*,
   exposing `/ws/feed` so your own consumers connect *in*. Many connections can exist
   here — one per browser tab / consumer.

`SubscriptionManager` is the glue: it reference-counts how many local consumers
(#2) care about each (exchange, token, mode), and only tells AngelOne (#1) about a
subscription the *first* time someone wants it, and only tells it to unsubscribe when
the *last* watcher goes away.

---

## 3. File-by-file walkthrough

### 3.1 `AngeloneMarketServiceApplication.java`
The Spring Boot entrypoint. Nothing unusual except `@EnableScheduling`, which is
**required** — every `@Scheduled` method in the app (daily re-login, token refresh,
feed heartbeat, daily instrument refresh) is a silent no-op without it.

---

### 3.2 `session/` — the one shared AngelOne login

This package answers exactly one question for the rest of the app: *"are we logged
in, and what's the current token?"* Nothing outside this package is allowed to talk to
`loginByPassword` or `generateTokens` directly.

- **`AngelSessionManager.java`** — the center of the whole service.
  - Holds the current `TokenData` (jwt/refresh/feed tokens) in an `AtomicReference`
    (safe for the WebSocket client and REST controllers to read concurrently while a
    scheduled thread might be writing it).
  - `login()` — full login: generates a live TOTP code via `TotpGenerator`, POSTs to
    `/rest/auth/angelbroking/user/v1/loginByPassword`, stores the resulting tokens.
  - `refresh()` — cheaper path: POSTs the existing `refreshToken` to
    `/rest/auth/angelbroking/jwt/v1/generateTokens` for a new token trio. Falls back to
    a full `login()` if there's no existing session to refresh from.
  - `applyAndNotify(...)` — shared success/failure handling for both paths above.
    Throws `IllegalStateException` on failure (`GlobalExceptionHandler` turns that into
    a 503), and on success calls `onSessionRenewed.run()` — a callback hook that
    `AngelFeedClient` registers itself into, so the WebSocket feed knows to reconnect
    with fresh credentials any time the session changes (login *or* refresh).
  - `@Scheduled` jobs: `dailyRelogin()` (cron `angel.daily-relogin-cron`, default
    8:45 AM — after midnight's forced expiry, before market pre-open) and
    `scheduledRefresh()` (every `angel.token-refresh-interval-ms`, default 25 minutes,
    proactively refreshing so live traffic never races an actual expiry). Both catch
    and log exceptions rather than crashing the scheduler thread.

- **`StartupLoginRunner.java`** — an `ApplicationRunner` that calls `login()` once at
  boot. Wrapped in try/catch so a temporarily-unreachable AngelOne doesn't prevent the
  whole app from starting — it just starts "up but feed-less" until a retry succeeds
  (manually via `/internal/session/relogin`, or the next scheduled cycle).

- **`TotpGenerator.java`** — decodes your Base32 TOTP secret and generates the current
  6-digit time-based code (`com.eatthepath.otp`, standard `HmacSHA1`, 30-second step —
  the same algorithm every authenticator app uses). This is what lets the daily
  re-login run **completely unattended** — no human ever types a 2FA code for this
  service.

- **`AngelAuthDtos.java`** — plain Java records for the auth request/response bodies
  (`LoginRequest`, `RefreshRequest`, `TokenData`, and a local copy of the
  `AngelEnvelope<T>` shape — see §3.4 for why there are *two* copies of this record in
  the codebase).

- **`SessionController.java`** — `GET /internal/session/status` (just returns
  `{"loggedIn": true/false}`) and `POST /internal/session/relogin` (a manual
  "break-glass" full re-login, for when something's gone wrong and you don't want to
  wait for the next scheduled cycle).

---

### 3.3 `client/` — talking to AngelOne's REST API

- **`AngelHeaders.java`** — builds the ~7 headers every single SmartAPI endpoint
  requires (`Content-Type`, `Accept`, `X-UserType: USER`, `X-SourceID: WEB`,
  `X-ClientLocalIP`, `X-ClientPublicIP`, `X-MACAddress`, `X-PrivateKey`), plus
  `Authorization: Bearer <jwt>` for authenticated calls. One place to fix a header
  format change or an IP update, instead of every call site.

- **`AngelRestCaller.java`** — a thin wrapper around Spring's `RestClient` with one
  piece of real logic: **retry-once-after-refresh**. If AngelOne returns HTTP 401
  (session gone stale between scheduled refreshes — AngelOne invalidated it
  server-side for some reason), it forces `AngelSessionManager.refresh()` and retries
  the exact same call once with the new token, instead of surfacing a raw 401/`AG8002`
  to the caller. This is what makes every `/market/*` endpoint self-healing without
  duplicating retry logic in every controller.

---

### 3.4 `marketdata/` — the actual product: read-only market data for callers

- **`MarketDataDtos.java`** — every request/response shape this package uses.
  - `AngelEnvelope<T>` — **note:** this is a *second, separate* copy of the same
    `{status, message, errorcode, data}` record that already exists in
    `session/AngelAuthDtos.java`. They're structurally identical but are two distinct
    Java types in two different packages. This is intentional package isolation (the
    `session` package's copy is private to auth flows; `marketdata`'s copy is what
    gets returned to your callers) rather than a bug, but it's worth knowing they
    aren't literally the same class if you ever go looking for "the" envelope type.
  - `QuoteRequest`, `CandleRequest`, `GreeksRequest`, `OiRequest` — mirror AngelOne's
    request bodies field-for-field.
  - `BrokerageOrder` / `BrokerageRequest` — mirrors AngelOne's snake_case brokerage
    request shape via explicit `@JsonProperty` on `product_type`, `transaction_type`,
    `symbol_name` (Java field names can't be snake_case). `token` is nullable here —
    see the resolution logic below.
  - `MarginPosition` / `MarginRequest` — the *exact* shape AngelOne's margin API wants
    (no `symbol` field — AngelOne only understands tokens for this endpoint).
  - `MarginPositionInput` / `MarginRequestInput` — what *your caller* is allowed to
    send in: same as `MarginPosition` plus an optional `symbol`. The service resolves
    `symbol` → `token` and strips `symbol` out before forwarding to AngelOne.
  - **Why brokerage uses Strings for quantity/price but margin uses `int`/`double`:**
    this isn't an inconsistency in this codebase — it's mirroring an actual
    inconsistency in AngelOne's own documented request shapes (the brokerage
    calculator's docs show quoted-string numbers, the margin calculator's docs show
    bare numbers). Both DTOs are correct for the endpoint they target.

- **`MarketDataService.java`** — this is where almost all the actual logic lives.
  Every public method follows the same shape: **build a cache key → check the cache →
  on a miss, call AngelOne via `AngelRestCaller` → store the result → return it.**
  - Eight separate Caffeine caches are injected (quote, candle, greeks, brokerage,
    margin, OI, intraday-eligible, cautionary) — each with its own TTL from
    `CacheProperties`, because a live quote needs a much shorter TTL (1 second) than,
    say, the cautionary-scrips list (1 hour) or a daily candle (1 hour).
  - **Why the constructor is hand-written instead of Lombok's `@RequiredArgsConstructor`:**
    several caches share the exact same generic type erasure
    (`Cache<String, Object>` appears four times; `Cache<String, CandleCacheEntry>`
    appears twice). Spring needs `@Qualifier` on each constructor parameter to know
    which bean is which, and relying on Lombok to faithfully copy `@Qualifier`
    annotations onto a generated constructor isn't guaranteed — so this one class
    opts out of Lombok's constructor generation.
  - **Symbol resolution pattern, repeated across every method:** each public method has
    a "friendly" overload that accepts an optional human-readable `symbol`/`symbols`
    alongside an optional raw `token`/`tokens`. If a token is explicitly given, it
    always wins (no ambiguity-guessing); only when it's absent does the method call
    into `InstrumentService` to resolve a symbol to a token. This is what lets a
    caller write `GET /market/quote?exchange=NSE&symbols=SBIN-EQ` instead of needing
    to already know that SBIN-EQ is token `3045`.
  - `getQuote` — `POST .../market/v1/quote/`. Cache key includes mode + the full
    exchange→tokens map.
  - `getCandles` — `POST .../historical/v1/getCandleData`. TTL varies **per interval**
    (a 1-minute candle expires in 30s; a 1-day candle can be cached for an hour) via
    `CacheProperties.candleTtlMs()`, defaulting to 60s for any interval not explicitly
    configured.
  - `getGreeks` — `POST .../marketData/v1/optionGreek`. No token resolution at all —
    AngelOne's own Greeks endpoint takes the underlying's plain name (e.g. `"TCS"})
    directly, so there was never a hardcoded-token problem here to fix.
  - `getBrokerage` — `POST .../brokerage/v1/estimateCharges`. Resolves each order's
    token independently (`resolveBrokerageToken`) — **before** building the cache key,
    so two requests that differ only in "gave the token explicitly" vs "let it resolve
    to the identical token" correctly hit the same cache entry.
  - `getMargin` — `POST .../margin/v1/batch`. Same resolve-then-cache pattern as
    brokerage; also strips the caller-only `symbol` field down to AngelOne's actual
    `MarginPosition` shape.
  - `getOi` — `POST .../historical/v1/getOIData`. Structurally a sibling of
    `getCandles` (same interval constants, same date format, its own TTL map). Only
    meaningful for F&O tokens; a cash-market symbol will resolve fine but AngelOne
    itself will return empty/erroring data — this service doesn't pre-validate that,
    by design (that's AngelOne's domain rule to enforce, not this gateway's).
  - `getIntradayEligible` — collapses AngelOne's two separate endpoints
    (`nseIntraday` / `bseIntraday`, identical response shape) into one method
    parameterized by `exchange` (must be `NSE` or `BSE`, validated with an
    `IllegalArgumentException` → 400 otherwise).
  - `getCautionaryScrips` — `GET .../securities/v1/cautionaryScrips`. No params on
    either side — always the one current ASM/GSM caution list. **Worth knowing:**
    AngelOne's own docs show a *request body* (`{"scripconsent":"yes"}`) right next to
    a GET-with-no-body code sample for this exact endpoint — a genuine inconsistency
    *in AngelOne's documentation*, not something introduced here. This service follows
    the code sample (bodyless GET), which is the more conventional shape for an HTTP
    GET and matches how every other AngelOne GET in this service is sent.

- **`MarketDataController.java`** — thin `@RestController` under `/market`, one method
  per service call. `@GetMapping` for pure reads (quote/candles/greeks/oi/
  intraday-eligible/cautionary), `@PostMapping` for the two calculators
  (brokerage/margin) because their bodies are lists of orders/positions, not a handful
  of scalars that fit cleanly as query params.

---

### 3.5 `instrument/` — symbol ⇄ token lookup

This is the piece that makes "pass a human-readable symbol instead of a magic numeric
token" possible everywhere in `marketdata/`.

- **`InstrumentDtos.java`** — `Instrument` mirrors one row of AngelOne's
  `OpenAPIScripMaster.json` dump exactly (including two fields, `exch_seg` and
  `tick_size`, that need explicit `@JsonProperty` because their JSON keys aren't
  camelCase). Also `ResolveResponse`, `SearchResponse`, and `InstrumentStatus` (the
  shape behind `GET /instruments/status`).

- **`InstrumentIndex.java`** — an immutable snapshot of the *entire* scrip master,
  rebuilt from scratch on every refresh (never mutated in place — see the class
  Javadoc for why: AngelOne only ever publishes the *full* dump, never a diff, so
  there's no reliable way to know what changed; the only safe move is
  throw-away-and-rebuild). Exposes three views over the same underlying data:
  - `bySymbolKey` — exact `"EXCHANGE|SYMBOL"` → `Instrument` (for resolve-by-symbol)
  - `byTokenKey` — exact `"EXCHANGE|TOKEN"` → `Instrument` (for resolve-by-token)
  - `all` — a flat list with **precomputed uppercase** symbol/name, used for `search`,
    so the search path never has to re-uppercase strings on every request.
  - `search(query, exchangeOrNull, limit)` — case-insensitive, **prefix matches first,
    then substring ("contains") matches**, linear scan (fine at tens-of-thousands-of-
    rows scale for infrequent calls; not built for millions of rows or high QPS).

- **`InstrumentProperties.java`** — its own `@ConfigurationProperties(prefix =
  "instrument")`, deliberately *separate* from `AngelProperties`. Reason: the scrip
  master dump lives on a different host
  (`margincalculator.angelone.in`, not `apiconnect.angelone.in`) and needs **zero**
  auth headers — it's a plain public file. Keeping its config isolated stops anyone
  from assuming it shares auth semantics with the "secure" endpoints.

- **`InstrumentService.java`** — the single shared instance for the whole app (same
  "exactly one, refreshed periodically, read by everyone" pattern as
  `AngelSessionManager`).
  - `resolveBySymbol`, `resolveByToken`, `search` — read-path delegates straight to
    the current `InstrumentIndex`.
  - `requireToken(exchange, symbol)` — the method every `marketdata/` caller actually
    uses. Throws a clear `IllegalArgumentException` (→ 400, via
    `GlobalExceptionHandler`) if the symbol is missing or unresolvable, instead of
    letting a silent `null` token flow into an AngelOne request that would then fail
    with a confusing upstream error.
  - `loadFromDisk()` — fast, no network, called first on startup so the app is never
    completely empty even if AngelOne's dump endpoint happens to be down at boot.
  - `refreshFromAngelOne()` — fetches the full dump, rebuilds the index, swaps it in
    atomically, and persists it to disk. Throws `IllegalStateException` if the dump
    comes back empty (keeps serving the previous snapshot rather than wiping itself
    out on a bad fetch).
  - `status()` — computes `stale: true/false` based on `instrument.stale-after-hours`
    (default 30h = one missed daily cycle + a buffer, so a single slow morning
    doesn't false-positive).
  - `scheduledRefresh()` — daily cron (`instrument.refresh-cron`, default 08:30 IST,
    just ahead of NSE pre-open), swallowing exceptions so a failed refresh degrades to
    "serving yesterday's data" rather than crashing anything.

- **`StartupInstrumentLoader.java`** — `ApplicationRunner`: disk-load first, then a
  live fetch attempt, both wrapped in their own try/catch so neither failure mode can
  take the whole app down at boot. Its Javadoc specifically documents a
  **previously-wrong assumption that was found and removed**: it used to carry
  `@Order(10)` with a comment claiming that made it run *after* `StartupLoginRunner` —
  that's backwards (unordered beans default to *last*, so `@Order(10)` would have made
  this run *first*, the opposite of the stated intent). It was removed rather than
  "fixed" because the ordering genuinely doesn't matter here (this loader has zero
  dependency on AngelOne login succeeding).

- **`InstrumentController.java`** — `GET /instruments/resolve`, `/instruments/token`,
  `/instruments/search`, `/instruments/status`, and `POST
  /internal/instruments/refresh` (manual break-glass refresh).

---

### 3.6 `feed/` — the one upstream connection to AngelOne's live tick stream

- **`AngelFeedClient.java`** — the only class in the entire app that opens a WebSocket
  connection *to* AngelOne.
  - `reconnect()` — closes any existing connection, opens a fresh one using the
    current session's `jwtToken`/`feedToken`, then calls `resubscribeAll()` — because
    a brand-new AngelOne WebSocket connection remembers nothing about what you were
    previously subscribed to.
  - Registered into `AngelSessionManager.setOnSessionRenewed(this::reconnect)` at
    startup — so *any* session change (fresh login or a scheduled refresh) triggers a
    feed reconnect with the new credentials, automatically.
  - `heartbeat()` — `@Scheduled(fixedRate = 25000)`, sends the literal text `"ping"`.
    25 seconds gives a safety margin under AngelOne's 30-second requirement. If the
    send itself fails, it triggers a `reconnect()` right there rather than just
    logging and hoping.
  - `sendSubscription(action, mode, exchangeType, tokens)` — builds and sends the
    subscribe/unsubscribe JSON frame. **See §9.1 for a real, if minor, bug found in
    the `correlationID` it generates here.**
  - Inner class `FeedHandler` — the actual `TextWebSocketHandler` for this outbound
    connection: ignores `"pong"` replies, logs anything else as a likely error frame,
    hands binary frames to `TickParser`, and logs transport errors/close events
    (recovery happens on the next heartbeat cycle, not immediately on error).

- **`SubscriptionManager.java`** — pure reference counting, no network calls of its
  own. `addWatcher` returns `true` only when a (exchangeType, token, mode) key goes
  from 0 watchers to 1 (i.e., *this* caller is the reason AngelOne needs to hear about
  it for the first time). `removeWatcher` returns `true` only when it goes from 1 to
  0. `activeGroupedByModeAndExchange()` is used solely to replay all currently-active
  subscriptions, batched by (exchange, mode), after a feed reconnect.

- **`Tick.java`** — the parsed record. Which fields are actually populated depends on
  which subscription mode produced the packet (documented right in the Javadoc):
  LTP mode only fills `ltp`; Quote mode adds OHLC + volume; SnapQuote isn't modeled in
  full (market depth isn't parsed — see §9.3).

- **`TickListener.java`** — a one-method functional interface; `AngelFeedClient`
  fans every parsed `Tick` out to every registered listener (in practice, just
  `PublicFeedWebSocketHandler`, but designed so more listeners could subscribe later —
  e.g. a metrics collector — without touching this class).

- **`TickParser.java`** — decodes the binary tick payload per the exact byte layout in
  AngelOne's WebSocket Streaming 2.0 docs (little-endian, offsets 0/1/2-27/27/35/43/
  51/59/67/91/99/107/115 for mode/exchangeType/token/sequence-number/timestamp/ltp/
  lastTradedQty/avgTradedPrice/volume/open/high/low/close respectively). Uses packet
  **length** (51 vs 123 vs 379 bytes) to know how far to keep parsing — this is
  actually the same signal AngelOne's own docs use to describe which mode you got back.
  I checked every offset in this class against the PDF's Section-1 payload table and
  they line up exactly (see §9.2 for one doc quirk this class correctly works around).

---

### 3.7 `broadcast/` — YOUR public-facing WebSocket

- **`FeedMessages.java`** — the three JSON shapes used on `/ws/feed`:
  `SubscribeRequest` (incoming), `TickFrame` (outgoing, wraps a `Tick` with
  `"type":"tick"`), `ErrorFrame` (outgoing, `"type":"error"`).

- **`PublicFeedWebSocketHandler.java`** — what your consumers actually talk to.
  Deliberately holds **zero** AngelOne credentials and knows nothing about TOTP, JWTs,
  or binary parsing.
  - Two indexes: `subscribersByKey` (which local `WebSocketSession`s care about a given
    key — used to fan a tick out) and `subscriptionsBySession` (the reverse — what a
    given session is subscribed to, used to clean up on disconnect).
  - `fanOut(tick)` — registered as a listener on `AngelFeedClient` at
    `@PostConstruct`. Looks up subscribers for the tick's key, serializes once, sends
    to every open session. Note the `synchronized (session)` block around
    `sendMessage` — `WebSocketSession.sendMessage` isn't safe for concurrent senders,
    and with a fan-out pattern, two different ticks (for two different keys) could in
    theory try to write to the same session at nearly the same instant.
  - `handleTextMessage` — parses the incoming JSON, and for `"subscribe"`, adds this
    session to the local index *and* asks `SubscriptionManager` whether this is a
    brand-new upstream subscription — only if it is does it actually call
    `angelFeedClient.sendSubscription(...)`. This is the exact mechanism that produces
    the "two consumers, one upstream subscription" behavior documented in
    `API_TESTING.md`.
  - `afterConnectionClosed` — walks everything that session was subscribed to and
    releases it, calling `sendSubscription(0, ...)` upstream for any key whose *last*
    watcher just disconnected. This is what keeps the 1000-subscription quota from
    slowly leaking as browser tabs come and go.

- **`WebSocketConfig.java`** — registers the handler at `/ws/feed` with a
  configurable allowed-origins list (`feed.allowed-origins`, defaults to
  `localhost:3000,localhost:8080`). The comment on this class is worth internalizing:
  origin checks are a **browser** concept — if your `trading` backend proxies this
  connection server-side instead of letting browsers connect directly, this setting
  becomes mostly irrelevant (server-to-server calls don't go through browser
  CORS/origin enforcement at all).

---

### 3.8 `config/` — wiring, caches, and the internal auth filter

- **`AngelProperties.java`** — binds everything under `angel.*`: credentials
  (`clientCode`, `pin`, `totpSecret`, `apiKey`), network identifiers AngelOne's headers
  require (`localIp`, `publicIp`, `macAddress`), the two base URLs (`restBaseUrl`,
  `wsUrl`), and the two cron/interval settings (`dailyReloginCron`,
  `tokenRefreshIntervalMs`).

- **`CacheProperties.java`** — binds `cache.*`: a flat TTL per simple cache
  (`quoteTtlMs`, `greeksTtlMs`, `brokerageTtlMs`, `marginTtlMs`,
  `intradayEligibleTtlMs`, `cautionaryTtlMs`) plus two **maps** keyed by AngelOne's
  interval constants (`candleTtlMs`, `oiTtlMs`) so a 1-minute candle and a 1-day candle
  can have wildly different, independently-tunable TTLs.

- **`InternalProperties.java`** — one field, `apiKey`, binds `internal.api-key` — the
  shared secret your own `trading` backend must send back on every call.

- **`AppConfig.java`** — general bean wiring:
  - `angelRestClient` — the `RestClient` used for every authenticated AngelOne REST
    call, base URL `apiconnect.angelone.in`.
  - `instrumentRestClient` — a **second, separate, header-free** `RestClient`, because
    the scrip master dump lives on `margincalculator.angelone.in` and needs zero auth.
    Reusing `angelRestClient` here would be wrong on both counts (wrong host, and it'd
    incorrectly imply this call carries the same auth semantics as the secure
    endpoints).
  - `objectMapper` — an explicit plain Jackson **2** `ObjectMapper` bean. The comment
    explains why this is necessary rather than redundant: Spring Boot 4.x's own
    autoconfigured HTTP message conversion uses **Jackson 3** (`tools.jackson.*`) by
    default and does not auto-expose a classic `com.fasterxml.jackson.databind`
    `ObjectMapper` bean, even though `jackson-databind` is present on the classpath
    (that only makes it *compilable*, not *autowireable*). `InstrumentService` needs a
    real Jackson 2 `ObjectMapper` for its own direct disk-cache read/write
    (`readValue`/`writeValue` against a `File`), so this bean exists purely for that.

- **`CacheConfig.java`** — eight named Caffeine cache beans. Six are simple
  `expireAfterWrite` caches; two (`candleCache`, `oiCache`) use Caffeine's *variable*
  `Expiry` interface instead, because their entries (`CandleCacheEntry`) carry their
  own per-entry TTL — a single flat annotation-level TTL literally cannot express
  "this 1-minute candle should expire far sooner than that 1-day candle" the way a
  variable expiry policy can. Both variable-TTL caches correctly implement
  `expireAfterRead` as "don't extend TTL on read" (returns `currentDuration`
  unchanged) — reads shouldn't keep stale data alive forever just because it's
  popular.

- **`InternalApiKeyFilter.java`** — a `OncePerRequestFilter` requiring header
  `X-Internal-Api-Key` to exactly match `internal.api-key` on every request **except**
  ones starting with `/ws/` (the public WebSocket is intentionally left open — see
  §9.4). Returns a raw 401 JSON body directly rather than going through
  `GlobalExceptionHandler` (this runs at the servlet-filter level, before Spring MVC's
  exception handling machinery is even in the request path).

- **`GlobalExceptionHandler.java`** *(technically `exception/`, documented here for
  flow)* — maps four exception categories to HTTP statuses:
  `IllegalArgumentException` → 400 (bad input, e.g. unresolvable symbol),
  `IllegalStateException` → 503 (session not established / login-refresh failed),
  `RestClientResponseException` → 502 (AngelOne itself returned an error),
  anything else → 500. **See §9.5** for one gap: malformed JSON request bodies aren't
  caught by any of these four and fall through to the generic 500 handler.

---

### 3.9 `exception/GlobalExceptionHandler.java`
Covered directly above in §3.8 to keep the exception-mapping story in one place.

---

## 4. Complete endpoint reference

All endpoints below (except `/ws/feed`) require header `X-Internal-Api-Key: <your
INTERNAL_API_KEY>`.

### 4.1 Session
| Method & path | Purpose |
|---|---|
| `GET /internal/session/status` | `{"loggedIn": true\|false}` |
| `POST /internal/session/relogin` | Force a fresh full login right now |

### 4.2 Instruments (symbol ⇄ token)
| Method & path | Purpose |
|---|---|
| `GET /instruments/resolve?exchange=NSE&symbol=SBIN-EQ` | Symbol → full instrument (incl. token) |
| `GET /instruments/token?exchange=NSE&token=3045` | Token → full instrument (reverse lookup) |
| `GET /instruments/search?query=SBIN&exchange=NSE&limit=20` | Fuzzy search (`exchange` optional, `limit` default 20, capped at 200) |
| `GET /instruments/status` | Is the index loaded, how many rows, from disk or live, how stale |
| `POST /internal/instruments/refresh` | Force an immediate re-fetch of the full scrip master |

### 4.3 Market data
| Method & path | AngelOne endpoint underneath | Notes |
|---|---|---|
| `GET /market/quote?mode=FULL\|OHLC\|LTP&exchange=NSE&tokens=3045,881` (or `&symbols=SBIN-EQ,RELIANCE-EQ`) | `market/v1/quote/` | `tokens` wins if both given; one exchange per call |
| `GET /market/candles?exchange=NSE&symbolToken=3045\|symbol=SBIN-EQ&interval=ONE_DAY&fromDate=yyyy-MM-dd hh:mm&toDate=...` | `historical/v1/getCandleData` | `symbolToken` wins if both given |
| `GET /market/greeks?name=TCS&expiryDate=25JAN2024` | `marketData/v1/optionGreek` | No token resolution — takes the underlying name directly |
| `POST /market/brokerage` `{"orders":[{...,"token"?:"..."}]}` | `brokerage/v1/estimateCharges` | `token` optional per order, resolved from `exchange`+`symbolName` |
| `POST /market/margin` `{"positions":[{...,"token"?:"...","symbol"?:"..."}]}` | `margin/v1/batch` | `token` optional per position, resolved from `exchange`+`symbol`; `orderType` has **no default** here (see §9.6) |
| `GET /market/oi?exchange=NFO&symbolToken=...\|symbol=...&interval=...&fromDate=...&toDate=...` | `historical/v1/getOIData` | Only meaningful for F&O (NFO/BFO) |
| `GET /market/intraday-eligible?exchange=NSE\|BSE` | `marketData/v1/nseIntraday` or `bseIntraday` | Whole-exchange list, no per-symbol call |
| `GET /market/cautionary` | `securities/v1/cautionaryScrips` | No params, always the full current list |

All `/market/*` responses come back as `{status, message, errorcode, data}`, mirroring
AngelOne's own envelope.

### 4.4 The live feed — `WS /ws/feed`
No `X-Internal-Api-Key` required (see §9.4 — this is intentional, but it's the one
genuinely open door in the whole service).

**Client → server, one JSON object per text frame:**
```json
{"action": "subscribe",   "exchangeType": 1, "token": "3045", "mode": 1}
{"action": "unsubscribe", "exchangeType": 1, "token": "3045", "mode": 1}
```

**Server → client:**
```json
{"type": "tick",  "tick": {"subscriptionMode": 1, "exchangeType": 1, "token": "3045", "ltp": 568.2, ...}}
{"type": "error", "message": "..."}
```
Disconnecting cleans up everything that session was watching automatically — no
explicit unsubscribe is required before closing.

---

## 5. Configuration reference (`application.yml`)

| Key | Default | What it controls |
|---|---|---|
| `server.port` | `8081` | This service's own port (your `trading` backend runs separately, e.g. on 8080) |
| `angel.client-code` / `angel.pin` / `angel.totp-secret` / `angel.api-key` | — (required, from `.env`) | Your AngelOne + SmartAPI-portal credentials |
| `angel.local-ip` / `angel.public-ip` / `angel.mac-address` | `127.0.0.1` / `127.0.0.1` / `AA:BB:CC:DD:EE:FF` | Sent as required SmartAPI headers on every call |
| `angel.rest-base-url` | `https://apiconnect.angelone.in` | Base URL for authenticated REST calls |
| `angel.ws-url` | `wss://smartapisocket.angelone.in/smart-stream` | AngelOne's live feed URL |
| `angel.daily-relogin-cron` | `0 45 8 * * *` (8:45 AM daily) | Forced fresh login, after midnight's session expiry |
| `angel.token-refresh-interval-ms` | `1500000` (25 min) | Proactive JWT refresh cadence |
| `internal.api-key` | `change-me-internal-key` (⚠️ change this) | Shared secret required in `X-Internal-Api-Key` on every non-`/ws/` request |
| `cache.quote-ttl-ms` | `1000` | Live quote cache TTL |
| `cache.candle-ttl-ms.<INTERVAL>` | 30s–1h depending on interval | Per-interval candle cache TTL, falls back to 60s if an interval isn't listed |
| `cache.oi-ttl-ms.<INTERVAL>` | 30s–1h depending on interval | Same idea, independent map for historical OI |
| `cache.greeks-ttl-ms` | `60000` | Option greeks cache TTL |
| `cache.brokerage-ttl-ms` / `cache.margin-ttl-ms` | `300000` (5 min) | Calculator caches — safe to hold longer since the result is a pure function of the request |
| `cache.intraday-eligible-ttl-ms` | `21600000` (6h) | Exchange-published reference list, changes only on a circular |
| `cache.cautionary-ttl-ms` | `3600000` (1h) | Same idea, ASM/GSM list |
| `feed.allowed-origins` | `http://localhost:3000,http://localhost:8080` | CORS-style origin allow-list for browsers connecting directly to `/ws/feed` |
| `instrument.dump-url` | AngelOne's `OpenAPIScripMaster.json` URL | Where the full scrip master is fetched from |
| `instrument.cache-file-path` | `./data/instruments-cache.json` | Disk cache path (gitignored — see README's Data & Cache Policy section) |
| `instrument.refresh-cron` | `0 30 8 * * *` (8:30 AM daily) | Daily full re-fetch, just ahead of NSE pre-open |
| `instrument.stale-after-hours` | `30` | How old the loaded snapshot can get before `/instruments/status` reports `stale: true` |

---

## 6. The caching model, end to end

Every `/market/*` method follows: **build a key from the (already-resolved) request →
`cache.getIfPresent(key)` → on a hit, return immediately, zero AngelOne calls → on a
miss, call AngelOne, store the result, return it.** Two things make this more than
"just slap `@Cacheable` on it":

1. **Symbol resolution happens before the cache key is built**, everywhere it
   applies. This means `?symbols=SBIN-EQ` and `?tokens=3045` (assuming SBIN-EQ really
   is token 3045) hit the *same* cache entry — the cache doesn't care which way the
   caller originally specified the instrument.
2. **Per-interval TTL for candles/OI** needed a data structure `@Cacheable`'s
   attribute-based TTL can't express, so those two caches carry their TTL inside the
   cached value itself (`CandleCacheEntry(payload, ttlMillis)`) and use Caffeine's
   `Expiry` SPI directly instead of a flat `expireAfterWrite`.

The practical effect: if 500 site visitors all load a chart for the same symbol within
the same second, AngelOne sees **one** upstream request, not 500 — which matters
because AngelOne's rate limits (e.g. 3 req/sec for candles) are enforced against your
one shared account, not per-visitor.

---

## 7. The session lifecycle, end to end

```
 boot
   │
   ▼
StartupLoginRunner.run() ──► AngelSessionManager.login()
   │  (TOTP generated fresh, POST loginByPassword)         if this fails: log it,
   │                                                        keep booting anyway —
   ▼                                                        feed is down until a
onSessionRenewed() fires ──► AngelFeedClient.reconnect()   retry succeeds
   │        (opens the one upstream WS, resubscribes
   │         anything SubscriptionManager already knows about
   │         — empty at first boot, non-empty after a reconnect)
   ▼
 running ── every ~25 min: AngelSessionManager.scheduledRefresh()
   │              (generateTokens; falls back to a full login() if refresh fails)
   │              → onSessionRenewed() fires again → feed reconnects with new creds
   │
   │        every 25s: AngelFeedClient.heartbeat() sends "ping"
   │              (failure here also triggers an immediate reconnect)
   │
   ▼
 every day at 08:45: AngelSessionManager.dailyRelogin()
        (forced full login — session hard-expires at midnight regardless of activity)
```

Any REST call hitting a stale token gets one automatic retry
(`AngelRestCaller`'s 401 handling) rather than surfacing the failure — so a token that
goes stale *between* scheduled refreshes (AngelOne invalidated it server-side for some
external reason) is still mostly invisible to callers.

---

## 8. The tick fan-out data flow, end to end

```
Browser A ──subscribe SBIN,LTP──► PublicFeedWebSocketHandler
                                        │
                                        ├─ adds A to subscribersByKey[key]
                                        ├─ SubscriptionManager.addWatcher(key) → true (first watcher!)
                                        └─ AngelFeedClient.sendSubscription(1, ...) ──► AngelOne WS

Browser B ──subscribe SBIN,LTP──► PublicFeedWebSocketHandler
                                        │
                                        ├─ adds B to subscribersByKey[key]
                                        ├─ SubscriptionManager.addWatcher(key) → false (already watched!)
                                        └─ (nothing sent to AngelOne — B rides the existing subscription)

AngelOne ──binary tick for SBIN──► AngelFeedClient.FeedHandler.handleBinaryMessage
                                        │
                                        ├─ TickParser.parse(bytes) → Tick
                                        └─ notifies every TickListener (i.e. fanOut in the handler)
                                                │
                                                ├─ looks up subscribersByKey[key] → {A, B}
                                                └─ sends the same serialized TickFrame to A and B
```

This is the exact mechanism `API_TESTING.md` §8a claims to have verified: one upstream
subscription, one upstream tick, fanned out to both consumers with (per their evidence)
identical `exchangeTimestamp`/`ltp` arriving roughly simultaneously.

---

## 9. Issues found during this review

Ranked roughly by how much I'd actually worry about each one.

### 9.1 `correlationID` can exceed AngelOne's documented 10-character limit — real, minor bug
`AngelFeedClient.sendSubscription()` builds:
```java
"correlationID", "amkt" + System.currentTimeMillis() % 10000000
```
`% 10000000` yields a value from `0` to `9999999` — up to **7 digits**. `"amkt"` is 4
characters. **4 + 7 = 11 characters**, but AngelOne's WebSocket docs describe
`correlationID` as *"a 10 character alphanumeric ID."* I confirmed the arithmetic
myself: at the high end of the range (`9999999`), the resulting string
`"amkt9999999"` is 11 characters long.

**Impact is low but real:** `correlationID` is optional and purely for your own
tracing (AngelOne echoes it back in error frames so you can match a request to a
response) — it doesn't affect subscribe/unsubscribe success. But if AngelOne validates
its length server-side and rejects or truncates over-length values, your error-frame
correlation could silently break exactly when you need it most (while debugging a
subscription error). **Fix:** shrink the modulus, e.g. `% 1000000` (6 digits) to
guarantee `"amkt" + 6 digits` = 10 characters exactly, or drop the prefix to something
shorter.

### 9.2 `TickParser` — the doc's own field-type/size mismatch (verified as an AngelOne doc quirk, not a code bug)
AngelOne's payload table lists the "Last Traded Price (LTP)" field as `int32` but
gives it a **size of 8 bytes** — inconsistent with a real int32 (4 bytes). I checked
byte offsets across the whole table: every other price field (open/high/low/close,
avgTradedPrice) is labeled `int64/long` at 8 bytes, and the offsets step by exactly 8
bytes throughout (43 → 51 → 59 → 67 → ... → 115). `TickParser.parse()` correctly reads
LTP with `buf.getLong(43)` (8 bytes), which matches the *size* column and every
neighboring field's stride — so the code is right and AngelOne's own doc has a
harmless typo in the *datatype* column for that one row. Nothing to change here; noting
it so a future reader doesn't "fix" the parser to use `getInt()` and break it.

### 9.3 SnapQuote (Full) mode market depth isn't parsed
`Tick.java`'s Javadoc says this outright: SnapQuote mode (mode 3) carries the richest
payload (379 bytes, including 5-level best-bid/best-ask depth per AngelOne's
"Section-2: Best Five Data" layout), but `TickParser` only parses up through the
123-byte Quote-mode fields even when a 379-byte packet arrives. This is a documented,
intentional gap (extend `TickParser`/`Tick` if you ever need order-book depth on the
live feed), not an oversight — flagging it here mainly so "why don't I see depth data
in mode-3 ticks" has an obvious answer.

### 9.4 `/ws/feed` is unauthenticated by design — a real production gap, already self-flagged
`InternalApiKeyFilter` explicitly skips anything under `/ws/`, and both `README.md`
and the class's own Javadoc call this out as intentional-but-risky: *"lock this down
before it's reachable outside your own network."* I'm re-flagging it here because it's
the single biggest actual security surface in the service if it's ever exposed to the
public internet directly (rather than proxied through your own backend, which is the
documented recommended pattern). This isn't a bug so much as an unfinished edge that's
already on your own radar — just making sure it doesn't get lost.

### 9.5 Malformed JSON bodies to `POST /market/brokerage` / `/market/margin` return 500, not 400
`GlobalExceptionHandler` maps four exception types (`IllegalArgumentException`,
`IllegalStateException`, `RestClientResponseException`, generic `Exception`). A
malformed/unparseable JSON body on either POST endpoint throws Spring's
`HttpMessageNotReadableException`, which isn't any of the first three — it falls into
the generic `Exception` handler and comes back as a 500 "internal error" instead of a
400 "bad request." Not dangerous, just a slightly misleading status code for what's
really a client input problem. **Fix (if you care):** add an
`@ExceptionHandler(HttpMessageNotReadableException.class)` returning 400.

### 9.6 `POST /market/margin` — no default for `orderType`
The controller Javadoc explicitly calls this out already: AngelOne's own docs say
`orderType` defaults to `"LIMIT"` if omitted, but this service does **not** apply that
default itself — an omitted `orderType` is forwarded to AngelOne as `null` verbatim,
relying entirely on AngelOne to apply its own default. This is a deliberate choice
(documented as "pass it explicitly to avoid relying on that default"), not an
oversight, but it's worth remembering that this service will not protect you from a
truly missing `orderType` the way it protects you from a missing `token`/`symbol`.

### 9.7 `InternalApiKeyFilter` uses a plain `String.equals()` for the shared-secret comparison
```java
if (provided == null || !provided.equals(internalProperties.apiKey())) { ... }
```
This is a non-constant-time comparison, which is a textbook timing-attack surface for
secret comparison in general. In this specific case the "attacker" would need
network access to a service that's already meant to be internal-only (and per §9.4,
the actually-exposed surface is the *unauthenticated* WebSocket, not this filter) —
so practical risk here is low, but it's a one-line fix
(`java.security.MessageDigest.isEqual(...)` on the UTF-8 bytes of both strings) if you
want to close it out anyway.

### 9.8 Three separate Jackson `ObjectMapper` instances exist, doing three different jobs, with no shared configuration
- `AppConfig.objectMapper()` — the one Spring-managed bean, used by `InstrumentService`
  for its disk cache.
- `PublicFeedWebSocketHandler` — creates its own `private final ObjectMapper
  objectMapper = new ObjectMapper();` field.
- `AngelFeedClient` — same pattern, its own private field.

None of this is wrong (there's no single shared configuration requirement — e.g.
no custom serializers/deserializers registered anywhere that would need to be
consistent across all three), and the `AppConfig` comment explains clearly *why* a
managed bean was needed for `InstrumentService` specifically. But if you ever do need
to register a shared Jackson feature (say, a custom date format), you'd need to
remember to touch three places, not one. Worth consolidating to a single injected
`ObjectMapper` if that day comes.

### 9.9 Build verification — what I could and couldn't check
I could not run an actual `mvn`/`./mvnw` build in this environment (no network route
to Maven Central from my sandbox). I did verify, via web search against current Spring
documentation, that:
- `spring-boot-starter-webmvc` (replacing the old `spring-boot-starter-web`) and
  `spring-boot-starter-webmvc-test` are both real, current artifacts for Spring Boot
  4.0/4.1 — the pom's comments about Boot 4's modular starter rename are accurate.
- Java 21 is indeed the stated baseline for Spring Boot 4.0, matching
  `<java.version>21</java.version>`.

I could **not** independently confirm that `spring-boot-starter-websocket-test`
(used only in `<scope>test</scope>` in `pom.xml`) is a real, separately-published
artifact the way its `-webmvc-test` sibling clearly is — my searches turned up plenty
on `spring-boot-starter-websocket` (the main starter, confirmed real for 4.0/4.1) but
nothing that explicitly confirmed a `-test` variant of it exists as its own Maven
artifact. **This is the one line in `pom.xml` I'd actually run `./mvnw dependency:resolve`
against before trusting the project builds clean** — if it turns out not to exist,
Boot 4's WebSocket testing support may simply live inside `spring-boot-starter-webmvc-test`
or the plain `spring-boot-starter-test`/"classic" starter instead, and that one
dependency line would need to be dropped or swapped.

### 9.10 No client-side rate limiting toward AngelOne beyond caching
AngelOne enforces hard per-second/per-minute/per-hour limits per client code (e.g. 3
req/sec for candle data, 10 req/sec for quotes — see the PDF's Rate Limit table). This
service's only defense against tripping those limits is the Caffeine caches — there's
no explicit token-bucket/leaky-bucket limiter sitting in front of `AngelRestCaller`
itself. In practice the caches absorb the overwhelming majority of realistic traffic
(the whole design point), but a burst of requests for **many different, uncached**
symbols within the same second (e.g. a bulk backfill job hitting `/market/candles` for
50 different tokens at once) could still exceed AngelOne's rate limit and get a 403,
which would currently surface to your caller as a 500 (it doesn't match any of
`GlobalExceptionHandler`'s specific cases — a 403 isn't separately handled from other
upstream statuses beyond the generic `RestClientResponseException` → 502 path, so it
actually *would* map to 502, not 500 — correcting myself: this one **is** already
handled reasonably via the `RestClientResponseException` branch, just without any
retry/backoff). Worth knowing as a scaling limit, not a bug.

---

## 10. Nothing wrong, but worth understanding: intentional exclusions

Per `README.md`'s Roadmap, these are **deliberately not built**, not missing by
accident: placing/modifying/cancelling orders, GTT rules, portfolio holdings/positions,
funds & margins (RMS), logout. All of these either move real money/orders on the one
shared account, or belong to a genuinely different per-user AngelOne login flow
(`publisher-login`) that's out of scope for a public read-only market-data fan-out
service. If a future task asks you to "just add order placement to this service," that
would be a deliberate scope change worth pausing on, not a small addition.

---

## 11. Quick glossary (for anyone new landing on this doc)

| Term | Meaning here |
|---|---|
| Token (AngelOne) | The numeric ID AngelOne uses internally for an instrument (e.g. `3045` = SBIN-EQ on NSE) — **not** an auth token |
| Token (auth) | jwt/refresh/feed tokens from login — context disambiguates which "token" is meant |
| Symbol | Human-readable instrument name, e.g. `SBIN-EQ` |
| Envelope | The `{status, message, errorcode, data}` wrapper every AngelOne response (and this service's `/market/*` responses) use |
| Watcher | A local `/ws/feed` consumer currently interested in a given (exchange, token, mode) |
| Upstream | AngelOne's own servers, from this service's point of view |
| Fan-out | Sending one upstream tick to many local WebSocket consumers at once |
| Dedup | Only telling AngelOne about a subscription once, no matter how many local consumers want it |
| Stale (instrument) | The loaded scrip-master snapshot is older than `instrument.stale-after-hours` — a signal to look into it, not proof the data is wrong |
| Break-glass | A manual endpoint (`/internal/session/relogin`, `/internal/instruments/refresh`) for forcing a recovery action instead of waiting for the next scheduled cycle |
