# API Testing Guide — angelone-market-service

Every endpoint, in order, with **curl** and **Postman** side by side, plus
what actually happened when this was tested for real — not just what should
theoretically happen. Where noted, the "expect" blocks are real verified
output, not guesses.

**Scope of this guide:** every endpoint that exists today — which, as of this
version, is everything that was ever on the Roadmap. For what's intentionally
excluded (orders, GTT, portfolio, funds), see the **Roadmap** section in
`README.md`. This file gets a new section per endpoint as each one actually
ships, not before — sections 11–13 are the newest three.

**Important change from earlier versions of this doc:** `/market/quote`,
`/market/candles`, `/market/oi`, `/market/brokerage`, and `/market/margin` used
to require you to already know AngelOne's numeric `token` for every symbol —
that requirement is gone. Every one of those now accepts a human-readable
**symbol** (with an `exchange`) as an alternative, resolved to a token
internally via the new `/instruments/*` endpoints (section 4 below), backed
by AngelOne's daily-refreshed scrip master. **`token`, when you do provide
it, always wins — resolution only kicks in when it's absent.** Nothing is
hardcoded anymore; if you don't know a token, you no longer need to go find
one externally.

**Everything that was ever queued up in `README.md`'s Roadmap is now built** — this
includes the three newest endpoints, `/market/oi`, `/market/intraday-eligible`, and
`/market/cautionary`, covered in sections 11–13 below. Nothing in this service is
"not started" anymore; the only remaining distinction is *built-and-live-tested* vs.
*built-and-code-reviewed-only* — see the status table immediately below.

Every REST request needs your internal key:
```bash
export INTERNAL_KEY=your_internal_api_key_from_env      # bash/zsh
set -x INTERNAL_KEY your_internal_api_key_from_env       # fish
```

---

## Status — what's actually built vs. what's not (read this first)

Excludes order placement, GTT, and portfolio/funds endpoints throughout — those are
intentionally out of scope for this service (see `README.md` → Roadmap for why).

| Endpoint | Status | Notes |
|---|---|---|
| `GET /internal/session/status` | ✅ built, tested | |
| `POST /internal/session/relogin` | ✅ built, tested | |
| `GET /instruments/status` | ✅ built | code-reviewed, not yet run against a live AngelOne dump fetch |
| `GET /instruments/resolve` | ✅ built | same caveat |
| `GET /instruments/token` | ✅ built | same caveat |
| `GET /instruments/search` | ✅ built | same caveat |
| `POST /internal/instruments/refresh` | ✅ built | same caveat |
| `GET /market/quote` | ✅ built, tested (token path) / ✅ built (symbol path, not yet live-tested) | |
| `GET /market/candles` | ✅ built, tested (token path) / ✅ built (symbol path, not yet live-tested) | |
| `GET /market/greeks` | ✅ built | code path right, every test attempt so far landed outside market hours |
| `POST /market/brokerage` | ✅ built, tested (token path) / ✅ built (symbol path, not yet live-tested) | |
| `POST /market/margin` | ✅ built, tested (token path) / ✅ built (symbol path, not yet live-tested) | |
| `GET /market/oi` | ✅ built | code-reviewed against AngelOne's `getOIData` shape (same envelope as candles), not yet run against a live session — see section 11 |
| `GET /market/intraday-eligible` | ✅ built | code-reviewed against `nseIntraday`/`bseIntraday`, not yet live-tested — see section 12 |
| `GET /market/cautionary` | ✅ built | code-reviewed against `cautionaryScrips`, not yet live-tested — see section 13 |
| `WS /ws/feed` | ✅ built, tested — subscribe/unsubscribe, dedup, fan-out, reconnect-replay all confirmed with real evidence (section 8a) | |
| Profile, funds/RMS, logout, portfolio, order placement, GTT | ❌ intentionally excluded | not this service's job — see README |

**Honest caveat on the "not yet live-tested" items above:** this sandbox has no
route to Maven Central, so none of the `instrument/` package or the new symbol-resolution
code paths have been run through even a real `mvn compile`, let alone against a live
AngelOne session. Everything above marked that way has been reviewed line-by-line
(method signatures, record field order, Jackson mappings, Spring wiring) but "should
work" is not the same claim as "has been observed working." Run `mvn compile` yourself
before trusting this in production, then work through this doc section by section.

---

1. **Environments** → new environment → two variables:
   `base_url` = `http://localhost:8081`, `internal_api_key` = your real key.
   Select this environment from the top-right dropdown.
2. **Collections** → new collection `angelone-market-service` →
   **Authorization** tab → type **API Key** → Key `X-Internal-Api-Key`,
   Value `{{internal_api_key}}`, Add to **Header**.
3. Every request created *inside* this collection inherits that header
   automatically — you never manually add it again.

---

## 0. Confirm clean startup first

Terminal should show, in this order:
```
AngelOne login succeeded
AngelOne feed WebSocket opened
Connected to AngelOne feed stream
```
Missing any of these → stop, fix that, nothing below will work otherwise.

---

## 1. Session status

**curl:**
```bash
curl -s localhost:8081/internal/session/status -H "X-Internal-Api-Key: $INTERNAL_KEY"
```
**Postman:** `GET {{base_url}}/internal/session/status`, auth inherited from collection.

**Expect:** `{"loggedIn": true}`

---

## 2. Auth is actually enforced (not a no-op)

**No header at all:**
```bash
curl -s -o /dev/null -w '%{http_code}\n' localhost:8081/internal/session/status
```
Postman: duplicate the request, set its own Authorization tab to **No Auth**.

**Wrong key:**
```bash
curl -s -o /dev/null -w '%{http_code}\n' localhost:8081/internal/session/status \
  -H "X-Internal-Api-Key: wrong"
```
Postman: duplicate, set Authorization to API Key directly with a bad value.

**Expect (both cases):** `401`. A `200` here means `InternalApiKeyFilter`
isn't actually wired up — treat as urgent if you see it.

---

## 3. Manual re-login (break-glass)

```bash
curl -s -X POST localhost:8081/internal/session/relogin -H "X-Internal-Api-Key: $INTERNAL_KEY"
```
Postman: `POST {{base_url}}/internal/session/relogin`, no body.

**Expect:** `{"status": true, "message": "re-login successful"}`, followed in
the logs by a fresh login + a feed reconnect. **Verified side effect:** if
you have an active `/ws/feed` subscription open when you do this, expect a
`"Replaying N subscription group(s) after reconnect"` log line right after —
this is the auto-resubscribe logic working, confirmed by real testing.

---

## 4. Instrument lookup — `/instruments/*`

This is the piece that makes everything below it stop needing a hardcoded
token. AngelOne publishes one big daily-regenerated JSON dump of every
tradable instrument across every exchange (~tens of thousands of rows) —
this service fetches that once a day (and once at startup), builds an
in-memory index, and persists a disk-cache copy so a restart between
refreshes still has yesterday's data instead of nothing.

**Test this section first** — sections 5, 6, 8, and 9 below assume the index
is loaded. If it isn't, symbol-based lookups on those will fail with a `400`
telling you to check `/instruments/status`.

### 4.1 Status — is the index actually loaded?

**curl:**
```bash
curl -s localhost:8081/instruments/status -H "X-Internal-Api-Key: $INTERNAL_KEY"
```
**Postman:** `GET {{base_url}}/instruments/status`

**Expect:**
```json
{"loaded": true, "count": 91234, "source": "live", "lastUpdated": "2026-08-18T08:30:04.123Z", "stale": false}
```
`source` is `"disk"` if startup only managed to load yesterday's cached copy
(AngelOne's dump was unreachable at boot), `"live"` once a real fetch has
succeeded, `"none"` if nothing has loaded yet at all. `count` in the tens of
thousands is normal — that's the whole exchange universe, not a filtered list.

`stale` is the new freshness signal — `true` once `lastUpdated` is older than
`instrument.stale-after-hours` (default 30h: one missed daily-cron cycle plus a
buffer). It doesn't mean the *data* is wrong, only that a refresh appears to have
been missed. See `README.md` → "Data & Cache Policy" for the full reasoning and
what to do about it (short version: `POST /internal/instruments/refresh`, section 4.5).

---

### 4.2 Resolve — symbol → token

Test symbol: SBIN-EQ, NSE (same one used throughout this doc — known token `3045`).

**curl:**
```bash
curl -s "localhost:8081/instruments/resolve?exchange=NSE&symbol=SBIN-EQ" \
  -H "X-Internal-Api-Key: $INTERNAL_KEY"
```
**Postman:** `GET {{base_url}}/instruments/resolve`, Params: `exchange=NSE`, `symbol=SBIN-EQ`

**Expect:**
```json
{"found": true, "instrument": {"token": "3045", "symbol": "SBIN-EQ", "name": "SBIN",
  "expiry": "", "strike": "-1.000000", "lotsize": "1", "instrumenttype": "",
  "exchSeg": "NSE", "tickSize": "5.000000"}}
```

**Not-found test** (`symbol=NOT-A-REAL-SYMBOL`): expect `{"found": false, "instrument": null}`
— a clean `200`, not an error, since "not found" is a valid answer to "does this exist."

---

### 4.3 Reverse lookup — token → symbol

**curl:**
```bash
curl -s "localhost:8081/instruments/token?exchange=NSE&token=3045" \
  -H "X-Internal-Api-Key: $INTERNAL_KEY"
```
**Postman:** `GET {{base_url}}/instruments/token`, Params: `exchange=NSE`, `token=3045`

**Expect:** same shape as 4.2's response.

---

### 4.4 Search — fuzzy lookup by partial symbol or name

Useful for an autocomplete/search box on your frontend — nobody types raw tokens by hand.

**curl:**
```bash
curl -s "localhost:8081/instruments/search?query=SBIN&exchange=NSE&limit=10" \
  -H "X-Internal-Api-Key: $INTERNAL_KEY"
```
**Postman:** `GET {{base_url}}/instruments/search`, Params: `query=SBIN`, `exchange=NSE` (optional — omit to search all exchanges), `limit=10` (optional, defaults to 20, capped at 200)

**Expect:**
```json
{"count": 4, "results": [
  {"token": "3045", "symbol": "SBIN-EQ", "name": "SBIN", ...},
  {"token": "11128", "symbol": "SBIN-AF", "name": "SBIN", ...},
  {"token": "4884", "symbol": "SBIN-BE", "name": "SBIN", ...},
  {"token": "12740", "symbol": "SBIN-BL", "name": "SBIN", ...}
]}
```
Prefix matches (symbol or name starting with your query) rank before
substring matches. Case-insensitive.

---

### 4.5 Break-glass refresh — `/internal/instruments/refresh`

Forces an immediate re-fetch of the full dump instead of waiting for the
8:30 AM cron. Same pattern as `/internal/session/relogin`.

**curl:**
```bash
curl -s -X POST localhost:8081/internal/instruments/refresh -H "X-Internal-Api-Key: $INTERNAL_KEY"
```
**Postman:** `POST {{base_url}}/internal/instruments/refresh`, no body.

**Expect:** same shape as 4.1's status response, with a fresh `lastUpdated` and `source: "live"`.

**Failure case:** if AngelOne's dump endpoint is unreachable entirely (DNS/connection
failure), expect a `500` — the in-memory index is untouched (old data keeps serving),
only the disk cache fails to update. If AngelOne's host *responds* but with a non-2xx
status, expect a `502 Bad Gateway` instead (`GlobalExceptionHandler` routes HTTP-level
upstream errors differently from connection failures — see `RestClientResponseException`
vs generic `Exception` handling). Either way, check `/instruments/status` afterward to
confirm the old snapshot is still there and still `loaded: true`.

---

## 5. Live quote — `/market/quote`

Test symbol: SBIN-EQ, NSE, token `3045` (liquid, always listed).

**curl:**
```bash
curl -s "localhost:8081/market/quote?mode=LTP&exchange=NSE&tokens=3045" \
  -H "X-Internal-Api-Key: $INTERNAL_KEY"
```
**Postman:** `GET {{base_url}}/market/quote`, **Params tab** (not the raw
URL bar — Postman assembles it for you): `mode=LTP`, `exchange=NSE`, `tokens=3045`.

**Expect:**
```json
{
  "status": true, "message": "SUCCESS", "errorcode": "",
  "data": {"fetched": [{"exchange": "NSE", "tradingSymbol": "SBIN-EQ", "symbolToken": "3045", "ltp": 571.75}], "unfetched": []}
}
```

Try `mode=FULL` for the richer shape, or `tokens=3045,2885` (adds
RELIANCE-EQ) for multiple symbols in one call — just edit the Params value
and resend in Postman, no need to rebuild the request.

**Bad token test** (`tokens=99999999`): expect `data.fetched` empty,
`data.unfetched` containing an AngelOne error (`AB1009` Symbol Not Found or similar).
This is AngelOne rejecting a garbage token it received — different from an
*unresolvable symbol*, which never reaches AngelOne at all (see below).

### 5.1 Same thing, but by symbol — no token needed

**curl:**
```bash
curl -s "localhost:8081/market/quote?mode=LTP&exchange=NSE&symbols=SBIN-EQ" \
  -H "X-Internal-Api-Key: $INTERNAL_KEY"
```
**Postman:** Params tab — `mode=LTP`, `exchange=NSE`, `symbols=SBIN-EQ` (drop `tokens` entirely).

**Expect:** identical response shape to the token-based example above — `symbols` gets resolved
to the same token `3045` internally via `/instruments/resolve`, then
proceeds exactly as if you'd passed `tokens=3045` yourself. Multiple symbols
work the same way `tokens` does: `symbols=SBIN-EQ,RELIANCE-EQ`.

**If both `tokens` and `symbols` are given:** `tokens` wins, `symbols` is
ignored — no ambiguity-guessing.

**Unresolvable symbol test** (`symbols=NOT-A-REAL-SYMBOL`): expect a `400`
from this service itself (not AngelOne) — `{"status": false, "message":
"No instrument found for exchange=NSE symbol=NOT-A-REAL-SYMBOL — check
spelling/exchange, or try GET /instruments/search?query=NOT-A-REAL-SYMBOL"}`.
This never reaches AngelOne at all, unlike a bad *token*, which does.

**Neither given test** (`mode=LTP&exchange=NSE`, no `tokens` or `symbols`):
expect a `400` — `"Provide either 'tokens' or 'symbols' (with 'exchange')"`.

---

## 6. Historical candles — `/market/candles`

**Format is `yyyy-MM-dd hh:mm` — year first, then month, then day.** The most
common mistake here is swapping month/day (e.g. typing `2026-14-08` meaning
"14th of August" — that's not a valid month and AngelOne will reject it with
a `400`, which surfaces through `GlobalExceptionHandler` as a `502 Bad
Gateway` with `"AngelOne upstream error: 400 BAD_REQUEST"`. That's the
service correctly reporting AngelOne's rejection — the fix is your date
format, not the code).

**curl:**
```bash
curl -s "localhost:8081/market/candles?exchange=NSE&symbolToken=3045&interval=ONE_DAY&fromDate=2026-08-01 09:15&toDate=2026-08-14 15:30" \
  -H "X-Internal-Api-Key: $INTERNAL_KEY"
```
**Postman:** Params tab — `exchange=NSE`, `symbolToken=3045`,
`interval=ONE_DAY`, `fromDate=2026-08-01 09:15`, `toDate=2026-08-14 15:30`.
Postman URL-encodes the space automatically; with curl you need the quotes
around the whole URL to protect it.

**Expect:**
```json
{"status": true, "message": "SUCCESS", "errorcode": "",
 "data": [["2026-08-01T09:15:00+05:30", 571.2, 574.35, 569.4, 572.05, 1234567], ...]}
```
Each row: `[timestamp, open, high, low, close, volume]`, passed through from
AngelOne unmodified. Empty `data: []` on a weekend/holiday range is normal,
not an error.

### 6.1 Same thing, but by symbol — no token needed

**curl:**
```bash
curl -s "localhost:8081/market/candles?exchange=NSE&symbol=SBIN-EQ&interval=ONE_DAY&fromDate=2026-08-01 09:15&toDate=2026-08-14 15:30" \
  -H "X-Internal-Api-Key: $INTERNAL_KEY"
```
**Postman:** Params tab — swap `symbolToken=3045` for `symbol=SBIN-EQ`, everything else identical.

**Expect:** identical response shape to the token-based example above.
**If both `symbolToken` and `symbol` are given:** `symbolToken` wins.
**Unresolvable symbol:** same `400` shape as the quote endpoint's symbol
failure above — this service rejects it before ever calling AngelOne.

---

## 7. Option greeks — `/market/greeks`

**Verified finding: this endpoint needs an active market session to return
anything.** Outside NSE trading hours (9:15 AM–3:30 PM IST, Mon–Fri), a
perfectly correct request comes back:
```json
{"status": false, "message": "No Data Available", "errorcode": "AB9019", "data": null}
```
That's expected off-hours behavior, confirmed by testing — not a bug in the
request or the code. Greeks (Delta/Gamma/Theta/Vega/IV) are computed live
from the current option premium and spot price, so there's genuinely nothing
to compute when the market's closed.

Also needs a **real, current** expiry date in `DDMMMYYYY` format — NSE moved
monthly stock/index expiry from the last **Thursday** to the last **Tuesday**
of the month in September 2025, so older examples online using Thursday
dates are stale. Check NSE's actual contract calendar for the exact date.

**curl:**
```bash
curl -s "localhost:8081/market/greeks?name=TCS&expiryDate=25AUG2026" \
  -H "X-Internal-Api-Key: $INTERNAL_KEY"
```
**Postman:** Params tab — `name=TCS`, `expiryDate=25AUG2026`.

**Expect, during market hours:** a JSON array under `data`, one object per
strike price, each with `delta`, `gamma`, `theta`, `vega`, `impliedVolatility`.

---

## 8. Live feed — `/ws/feed`

`curl` can't hold a WebSocket open — use Postman's native WebSocket support,
or `websocat` from the terminal.

**Postman:** Sidebar → **New → WebSocket Request → Raw** (not Socket.IO —
this service speaks plain WebSocket text frames). URL:
`ws://localhost:8081/ws/feed`. Click **Connect**.

**websocat:**
```bash
nix-shell -p websocat
websocat ws://localhost:8081/ws/feed
```

**Subscribe:**
```json
{"action": "subscribe", "exchangeType": 1, "token": "3045", "mode": 1}
```

**Verified finding: AngelOne pushes an immediate snapshot tick on subscribe,
even outside market hours** — in testing, a tick arrived under 100ms after
subscribing, well outside trading hours. So you should see a tick back
almost immediately regardless of when you test, not just during live
trading — that earlier assumption in this project's notes was wrong and has
been corrected here.

**Expect:**
```json
{"type": "tick", "tick": {"subscriptionMode": 1, "exchangeType": 1, "token": "3045", "exchangeTimestamp": 1786757107544, "ltp": 1067.7, ...}}
```

**Unsubscribe:**
```json
{"action": "unsubscribe", "exchangeType": 1, "token": "3045", "mode": 1}
```

**Malformed input** (send literal non-JSON text): expect
`{"type": "error", "message": "malformed request: ..."}`.

---

## 8a. Subscription dedup + fan-out — the actual point of this service

This is the test that matters most. Verified with real evidence — here's
exactly what to look for.

**Setup:** open two separate connections (two Postman WebSocket tabs, or two
`websocat` processes) to `/ws/feed`.

**Step 1** — connection A subscribes:
```json
{"action": "subscribe", "exchangeType": 1, "token": "3045", "mode": 1}
```
Check the server logs immediately after. Expect:
```
DEBUG ... SubscriptionManager : First watcher for Key[exchangeType=1, token=3045, mode=1] — will subscribe upstream
```

**Step 2** — connection B subscribes to the **identical** symbol/mode. Check
the logs again. **You should see nothing new** — no second "First watcher"
line, no second upstream subscribe call. This silence is the pass condition,
not a failure.

**What confirmed this worked in real testing:** both connections received a
tick with the **exact same `exchangeTimestamp` and `ltp`**, roughly 1ms
apart — proof it was one upstream tick, fanned out to both listeners, not
two independently-fetched values that happened to match.

**Step 3** — unsubscribe connection A only. Logs should show **nothing** (B
is still watching, so the upstream subscription must stay alive). Then
unsubscribe B too — *now* expect:
```
DEBUG ... SubscriptionManager : Last watcher gone for Key[...] — will unsubscribe upstream
```

**Bonus check, confirmed for free during testing:** if a token refresh or
manual relogin triggers a feed reconnect while both A and B are still
subscribed, expect exactly:
```
Replaying 1 subscription group(s) after reconnect
```
**One** group, not two — direct proof the reference count is stored once per
symbol/mode, never duplicated per consumer.

---

## 9. Brokerage calculator — `/market/brokerage`

Estimates charges/taxes for a hypothetical order (or a basket of them). **Never places
anything** — it only asks AngelOne what an order like this would cost.

### 9.0 Field reference — what's required, what's optional, what's valid

**Updated:** `token` is now **optional** at this service's boundary. If you omit it,
`MarketDataService.getBrokerage` resolves it from `exchange`+`symbolName` via the
instrument index (section 4) before the request ever reaches AngelOne. If you provide
`token` explicitly, it's used as-is and no resolution happens. AngelOne itself still
requires `token` on the wire — this service just fills it in for you when it can.

**Honest note on the rest:** this service still does **no validation of its own** beyond
resolving the token — `BrokerageOrder` has no `@NotNull`/`@Pattern` checks on the other
fields. It translates your camelCase field names into the snake_case AngelOne's API
actually expects (see field-name note below), then forwards the request as-is. So
"required" below means *required by AngelOne*, not enforced by this service, for every
field except `token`.

| JSON field you send | Sent to AngelOne as | Type | Required | Valid values / example |
|---|---|---|---|---|
| `productType` | `product_type` | string | Yes | `DELIVERY`, `INTRADAY`, `MARGIN`, `CARRYFORWARD`, `BO` |
| `transactionType` | `transaction_type` | string | Yes | `BUY`, `SELL` |
| `quantity` | `quantity` | string (numeric) | Yes | e.g. `"10"` — send as a **string**, not a JSON number |
| `price` | `price` | string (numeric) | Yes | e.g. `"800"` — also a **string** |
| `exchange` | `exchange` | string | Yes | `NSE`, `BSE`, `NFO`, `BFO`, `MCX`, `CDS`, `NCDEX` |
| `symbolName` | `symbol_name` | string | Yes | Trading symbol, e.g. `"SBIN-EQ"` — also doubles as the lookup key when `token` is omitted |
| `token` | `token` | string | **No** — resolved from `exchange`+`symbolName` if omitted | AngelOne's numeric symbol token, e.g. `"3045"` |

The whole request body is `{"orders": [ {...}, {...} ]}` — `orders` itself is required
and must be a non-empty array; each element needs at minimum `productType`,
`transactionType`, `quantity`, `price`, `exchange`, `symbolName`, and either `token` or
a `symbolName` that resolves.

**Field name note:** the JSON *you* send uses plain camelCase (`productType`,
`transactionType`, `symbolName`) — the service translates these to the snake_case
(`product_type`, `transaction_type`, `symbol_name`) AngelOne's API actually expects.
`quantity`, `price`, `exchange`, and `token` pass straight through unchanged since
AngelOne already uses those exact lowercase names.

---

### 9.1 Valid request — single BUY order (token given explicitly)

**curl:**
```bash
curl -s -X POST localhost:8081/market/brokerage -H 'Content-Type: application/json' \
  -H "X-Internal-Api-Key: $INTERNAL_KEY" \
  -d '{"orders": [{"productType":"DELIVERY","transactionType":"BUY","quantity":"10","price":"800","exchange":"NSE","symbolName":"SBIN-EQ","token":"3045"}]}'
```
**JSON body (Postman → Body → raw → JSON):**
```json
{
  "orders": [
    {
      "productType": "DELIVERY",
      "transactionType": "BUY",
      "quantity": "10",
      "price": "800",
      "exchange": "NSE",
      "symbolName": "SBIN-EQ",
      "token": "3045"
    }
  ]
}
```
**Postman:** `POST {{base_url}}/market/brokerage`, Body → raw → JSON, same payload as above.

**Expect:**
```json
{
  "status": true, "message": "SUCCESS", "errorcode": "",
  "data": {"summary": {"total_charges": 3.0796, "trade_value": 8000, "breakup": [...]}, "charges": [...]}
}
```

---

### 9.2 Same thing, but no `token` at all — resolved from `symbolName`

**curl:**
```bash
curl -s -X POST localhost:8081/market/brokerage -H 'Content-Type: application/json' \
  -H "X-Internal-Api-Key: $INTERNAL_KEY" \
  -d '{"orders": [{"productType":"DELIVERY","transactionType":"BUY","quantity":"10","price":"800","exchange":"NSE","symbolName":"SBIN-EQ"}]}'
```
**JSON body (Postman → Body → raw → JSON):**
```json
{
  "orders": [
    {
      "productType": "DELIVERY",
      "transactionType": "BUY",
      "quantity": "10",
      "price": "800",
      "exchange": "NSE",
      "symbolName": "SBIN-EQ"
    }
  ]
}
```
Notice: no `token` field at all.

**Expect:** identical response to 9.1 — the service resolves `NSE`+`SBIN-EQ` to token
`3045` internally before ever calling AngelOne, so the result is the same. This is now
the normal way to call this endpoint if you don't already have tokens cached
client-side; 9.1's explicit-token form still works and always takes priority if given.

---

### 9.3 Valid request — SELL order (token given explicitly)

Same shape, just flip `transactionType`:
```bash
curl -s -X POST localhost:8081/market/brokerage -H 'Content-Type: application/json' \
  -H "X-Internal-Api-Key: $INTERNAL_KEY" \
  -d '{"orders": [{"productType":"DELIVERY","transactionType":"SELL","quantity":"10","price":"800","exchange":"NSE","symbolName":"SBIN-EQ","token":"3045"}]}'
```
**JSON body (Postman → Body → raw → JSON):**
```json
{
  "orders": [
    {
      "productType": "DELIVERY",
      "transactionType": "SELL",
      "quantity": "10",
      "price": "800",
      "exchange": "NSE",
      "symbolName": "SBIN-EQ",
      "token": "3045"
    }
  ]
}
```
**Expect:** same shape as 8.1 — SELL-side charges typically differ slightly from BUY
(STT applies differently on the two sides), but the envelope structure is identical.

---

### 9.4 Valid request — multi-order basket (mixed: one with token, one without)

Two orders in one call — confirms `summary` reflects the combined trade while each item
still gets its own line under `charges`:
```bash
curl -s -X POST localhost:8081/market/brokerage -H 'Content-Type: application/json' \
  -H "X-Internal-Api-Key: $INTERNAL_KEY" \
  -d '{"orders": [
        {"productType":"DELIVERY","transactionType":"BUY","quantity":"10","price":"800","exchange":"NSE","symbolName":"SBIN-EQ","token":"3045"},
        {"productType":"DELIVERY","transactionType":"BUY","quantity":"10","price":"800","exchange":"BSE","symbolName":"PICLL151223","token":"726131"}
      ]}'
```
**JSON body (Postman → Body → raw → JSON):**
```json
{
  "orders": [
    {
      "productType": "DELIVERY",
      "transactionType": "BUY",
      "quantity": "10",
      "price": "800",
      "exchange": "NSE",
      "symbolName": "SBIN-EQ",
      "token": "3045"
    },
    {
      "productType": "DELIVERY",
      "transactionType": "BUY",
      "quantity": "10",
      "price": "800",
      "exchange": "BSE",
      "symbolName": "PICLL151223",
      "token": "726131"
    }
  ]
}
```
**Expect:** `data.summary.trade_value` = the sum across both orders, and `data.charges`
has two entries, one per order — same as AngelOne's own documented shape.

---

### 9.5 Error cases (not independently verified against a live account — based on
### AngelOne's documented error codes; treat these as expected, confirm against your
### own account before relying on the exact `errorcode`)

**Unresolvable symbol, no token given** (`symbolName: "NOT-A-REAL-SYMBOL"`, no `token`):
```bash
curl -s -X POST localhost:8081/market/brokerage -H 'Content-Type: application/json' \
  -H "X-Internal-Api-Key: $INTERNAL_KEY" \
  -d '{"orders": [{"productType":"DELIVERY","transactionType":"BUY","quantity":"10","price":"800","exchange":"NSE","symbolName":"NOT-A-REAL-SYMBOL"}]}'
```
Expect a `400` **from this service**, not from AngelOne — resolution fails before the
request is ever sent upstream: `{"status": false, "message": "No instrument found for
exchange=NSE symbol=NOT-A-REAL-SYMBOL — check spelling/exchange, or try GET
/instruments/search?query=NOT-A-REAL-SYMBOL"}`. This is a behavior change from
`token`-only versions of this endpoint, which would have forwarded a `null` token
straight to AngelOne and surfaced whatever error AngelOne gave back instead.

**Invalid enum value** (`productType: "NOT_A_REAL_TYPE"`): expect `status: false`,
likely `AB1012 Invalid Product Type`.

**Bad/unknown token** (`token: "99999999"`): expect `status: false`, likely `AB1009
Symbol Not Found` or `AB1018 Failed to get symbol details` — same failure shape as a bad
token on `/market/quote`.

**Empty `orders` array** (`{"orders": []}`): forwarded as-is; expect AngelOne to reject
with a generic parameter/validation error rather than a per-field one, since there's no
order to validate against.

**Non-numeric `quantity`/`price`** (e.g. `"quantity": "ten"`): both fields are typed as
`String` in this service on purpose (AngelOne's own docs specify them as strings, not
JSON numbers) — so a non-numeric string is forwarded unchanged and it's AngelOne, not
this service, that will reject it.

---

## 10. Margin calculator — `/market/margin`

Real-time margin required for a basket of up to 50 hypothetical positions. Also never
places anything.

**Updated:** same pattern as brokerage above — `token` is now optional per position.
The request body shape also changed name: it's now `MarginPositionInput` (an extra
optional `symbol` field alongside the optional `token`). AngelOne's own margin API
still only understands `token`, never a symbol — this service resolves `symbol` to a
`token` internally, then strips `symbol` back out before forwarding, so what actually
goes over the wire to AngelOne is unchanged.

### 10.1 Valid request — token given explicitly

**curl:**
```bash
curl -s -X POST localhost:8081/market/margin -H 'Content-Type: application/json' \
  -H "X-Internal-Api-Key: $INTERNAL_KEY" \
  -d '{"positions": [{"exchange":"NFO","qty":50,"price":0,"productType":"INTRADAY","token":"67300","tradeType":"BUY","orderType":"MARKET"}]}'
```
**JSON body (Postman → Body → raw → JSON):**
```json
{
  "positions": [
    {
      "exchange": "NFO",
      "qty": 50,
      "price": 0,
      "productType": "INTRADAY",
      "token": "67300",
      "tradeType": "BUY",
      "orderType": "MARKET"
    }
  ]
}
```
**Postman:** `POST {{base_url}}/market/margin`, Body → raw → JSON, same payload as above.

**Expect:**
```json
{
  "status": true, "message": "SUCCESS", "errorcode": "",
  "data": {"totalMarginRequired": 29612.35, "marginComponents": {"netPremium": 5060, "spanMargin": 0, ...}}
}
```

### 10.2 Same thing, but no `token` — resolved from `symbol`

**curl:**
```bash
curl -s -X POST localhost:8081/market/margin -H 'Content-Type: application/json' \
  -H "X-Internal-Api-Key: $INTERNAL_KEY" \
  -d '{"positions": [{"exchange":"NFO","qty":50,"price":0,"productType":"INTRADAY","symbol":"NIFTY28AUG25FUT","tradeType":"BUY","orderType":"MARKET"}]}'
```
**JSON body (Postman → Body → raw → JSON):**
```json
{
  "positions": [
    {
      "exchange": "NFO",
      "qty": 50,
      "price": 0,
      "productType": "INTRADAY",
      "symbol": "NIFTY28AUG25FUT",
      "tradeType": "BUY",
      "orderType": "MARKET"
    }
  ]
}
```
**Expect:** same shape as 10.1 — `symbol` resolves to a token internally, `symbol`
itself is dropped before the request reaches AngelOne (AngelOne's API has no such
field). Use a real, current NFO contract symbol from `/instruments/search?query=NIFTY&exchange=NFO`
if this specific expiry has since passed.

**Unresolvable symbol, no token given:** same `400`-from-this-service shape as the
brokerage endpoint's equivalent case above — never reaches AngelOne.

---

**Verified finding: `marginComponents` frequently comes back all-zero (`netPremium`,
`spanMargin`, `marginBenefit`, `deliveryMargin`, `nonNFOMargin`, `totOptionsPremium` all
`0.0`) while `totalMarginRequired` is still populated with a real, non-zero number, and no
`marginBreakup`/`optionsBuy` sections appear at all** — even on a request AngelOne accepts
with `status: true`. This isn't something `MarketDataService.getMargin` does — it forwards
AngelOne's response through completely unmodified (`Object`-typed, no field mapping), so a
zeroed-out breakdown is AngelOne's own response, not a bug here. AngelOne's SmartAPI forum
has multiple confirmed reports of the exact same thing ("Margin Calculator API response
coming as 0 intermittently") with no error and no bad parameters — it's an acknowledged
intermittent server-side quirk on AngelOne's side, not something to chase down in this repo.
`totalMarginRequired` alone is the field to trust; treat `marginComponents` as best-effort.

**`orderType` note:** AngelOne's docs say this defaults to `"LIMIT"` if you omit it, but
this service always sends whatever you pass — always include it explicitly (`LIMIT`,
`MARKET`, `STOPLOSS_LIMIT`, or `STOPLOSS_MARKET`) rather than relying on that default.

**Bad token test** (`token: "99999999"`): expect an error envelope (`status: false`) with
an AngelOne error code — same failure shape as a bad token on `/market/quote`. This is
different from an unresolvable *symbol* (10.2's error case) — a bad token still reaches
AngelOne and gets an AngelOne-side rejection; a bad symbol never leaves this service.

---

## 11. Historical open interest — `/market/oi`

Sibling of `/market/candles` — same date format (`yyyy-MM-dd hh:mm`), same interval
constants, same max-days-per-request caps per AngelOne's docs (see README/SmartAPI docs
for the table). Only meaningful for F&O instruments (NFO/BFO); OI isn't a cash-market
concept, so pointing this at an NSE/BSE cash-market token will reach AngelOne fine but
likely come back with nothing useful — that's AngelOne's own domain rule, not a bug here.

**curl — by token:**
```bash
curl -s "localhost:8081/market/oi?exchange=NFO&symbolToken=46823&interval=THREE_MINUTE&fromDate=2026-08-06 11:15&toDate=2026-08-06 12:00" \
  -H "X-Internal-Api-Key: $INTERNAL_KEY"
```
**Postman:** `GET {{base_url}}/market/oi`, Params tab — `exchange=NFO`,
`symbolToken=46823`, `interval=THREE_MINUTE`, `fromDate=2026-08-06 11:15`,
`toDate=2026-08-06 12:00`.

**Expect:**
```json
{"status": true, "message": "SUCCESS", "errorcode": "",
 "data": [{"time": "2026-08-06T11:15:00+05:30", "oi": 1661100}]}
```
Empty `data: []` outside market hours or on an expired contract is expected, not an error.

### 11.1 Same thing, but by symbol — no token needed

**curl:**
```bash
curl -s "localhost:8081/market/oi?exchange=NFO&symbol=NIFTY28AUG25FUT&interval=THREE_MINUTE&fromDate=2026-08-06 11:15&toDate=2026-08-06 12:00" \
  -H "X-Internal-Api-Key: $INTERNAL_KEY"
```
**Postman:** swap `symbolToken=46823` for `symbol=NIFTY28AUG25FUT` (use a real, current
contract from `/instruments/search?query=NIFTY&exchange=NFO` if this expiry has passed).

**Expect:** identical shape to the token-based example. **If both `symbolToken` and
`symbol` are given, `symbolToken` wins.** Unresolvable symbol → same `400`-from-this-service
shape as `/market/candles`' equivalent case.

---

## 12. Intraday-eligible scrips — `/market/intraday-eligible`

Which scrips AngelOne currently allows for intraday trading on an exchange, and each
one's margin multiplier. Pure reference data, no token/symbol involved — you get the
whole current list back in one call, not a per-symbol lookup.

**curl:**
```bash
curl -s "localhost:8081/market/intraday-eligible?exchange=NSE" \
  -H "X-Internal-Api-Key: $INTERNAL_KEY"
```
**Postman:** `GET {{base_url}}/market/intraday-eligible`, Params tab — `exchange=NSE`
(or `exchange=BSE`).

**Expect:**
```json
{"status": true, "message": "SUCCESS", "errorcode": "",
 "data": [
   {"exchange": "NSE", "SymbolName": "CHEMPLASTS", "Multiplier": "5.0"},
   {"exchange": "NSE", "SymbolName": "SANGHVIMOV", "Multiplier": "5.0"}
 ]}
```

**Invalid exchange test** (`exchange=NFO` or anything other than `NSE`/`BSE`): expect a
`400` **from this service**, not AngelOne — `{"status": false, "message": "exchange must
be NSE or BSE for /market/intraday-eligible, got: NFO"}`. AngelOne only exposes this
lookup for NSE and BSE; this is checked before any upstream call is made.

---

## 13. Cautionary scrips — `/market/cautionary`

ASM/GSM (Additional/Graded Surveillance Measure) caution-flagged scrips. No params —
it's always the single, current, full list across exchanges.

**curl:**
```bash
curl -s localhost:8081/market/cautionary -H "X-Internal-Api-Key: $INTERNAL_KEY"
```
**Postman:** `GET {{base_url}}/market/cautionary`, no params.

**Expect:**
```json
{"status": true, "message": "SUCCESS", "errorcode": "",
 "data": [
   {"token": "532083", "symbol": "SHKALYN", "message": "Security is under Gross settlement (Trade for Trade)"},
   {"token": "512477", "symbol": "BETXIND", "message": "Stage 1 Long Term ASM scrip. ..."}
 ]}
```
Field content is whatever AngelOne currently has flagged — the list changes as
exchanges add/remove surveillance actions, so exact entries here will differ from what
you actually see.

---

## Endpoint reference

| Method | Endpoint | Auth | Notes |
|---|---|---|---|
| GET | `/internal/session/status` | Internal | `{loggedIn: bool}` |
| POST | `/internal/session/relogin` | Internal | Break-glass manual re-login |
| GET | `/instruments/resolve` | Internal | `?exchange=&symbol=` → `{found, instrument}` |
| GET | `/instruments/token` | Internal | `?exchange=&token=` → `{found, instrument}` (reverse lookup) |
| GET | `/instruments/search` | Internal | `?query=&exchange=&limit=` → `{count, results}`, fuzzy, exchange optional |
| GET | `/instruments/status` | Internal | `{loaded, count, source, lastUpdated}` |
| POST | `/internal/instruments/refresh` | Internal | Break-glass manual re-fetch of the full scrip master |
| GET | `/market/quote` | Internal | `?mode=LTP\|OHLC\|FULL&exchange=&tokens=` **or** `&symbols=` (tokens wins if both given) |
| GET | `/market/candles` | Internal | `?exchange=&symbolToken=&interval=&fromDate=&toDate=` **or** `&symbol=` in place of `symbolToken` |
| GET | `/market/greeks` | Internal | `?name=&expiryDate=` — needs live market hours; never took a token, unaffected by this change |
| GET | `/market/oi` | Internal | `?exchange=&symbolToken=&interval=&fromDate=&toDate=` **or** `&symbol=` in place of `symbolToken`; F&O only |
| GET | `/market/intraday-eligible` | Internal | `?exchange=NSE\|BSE` → eligible scrips + margin multiplier for that exchange |
| GET | `/market/cautionary` | Internal | no params → full current ASM/GSM caution-flagged list |
| POST | `/market/brokerage` | Internal | `{orders: [...]}` — each order's `token` optional if `exchange`+`symbolName` given; charges estimate, never places an order |
| POST | `/market/margin` | Internal | `{positions: [...]}` — each position's `token` optional if `exchange`+`symbol` given; margin estimate, never places an order |
| WS | `/ws/feed` | None* | JSON subscribe/unsubscribe |

\* Intentionally unauthenticated — lock down before exposing outside your own network.

---

## Error codes reference

| Code | Meaning | Where you'll see it |
|---|---|---|
| `AB1009` | Symbol Not Found | Bad *token* in quote/candle requests — this reached AngelOne and AngelOne rejected it |
| `AB1018` | Failed to get symbol details | Same as above |
| `AG8001`/`AG8002` | Invalid/Expired Token | Should self-heal via automatic refresh-and-retry |
| `AB9019` | No Data Available | `/market/greeks` outside market hours — confirmed expected, not a bug |
| `400 BAD_REQUEST` (wrapped as `502` by this service) | Malformed request to AngelOne | Almost always a date-format typo on `/market/candles` |
| `403` (raw HTTP) | Rate limit exceeded | Only from hammering an endpoint in a tight loop while testing — normal cached usage shouldn't hit this |
| `400` from **this service** (not AngelOne) | Unresolvable *symbol*, or neither `token` nor `symbol`/`symbols` given | `/market/quote`, `/market/candles`, `/market/brokerage`, `/market/margin` — never reaches AngelOne at all; message names the exact symbol/exchange that failed and suggests `/instruments/search` |

---

## Appendix — full request/response JSON for every endpoint, in one place

Everything above is a walkthrough with narrative and verified findings woven in. This
appendix is the opposite: no narrative, just every endpoint's complete request and
response shape, back to back, for quick copy-paste reference. GET endpoints don't have
a JSON body (that's not how HTTP works) — their "request" is the full query string.

### A.1 `GET /internal/session/status`

No params, no body.

**Response:**
```json
{"loggedIn": true}
```

---

### A.2 `POST /internal/session/relogin`

No body.

**Response:** same shape as A.1.

---

### A.3 `GET /instruments/status`

No params, no body.

**Response:**
```json
{
  "loaded": true,
  "count": 91234,
  "source": "live",
  "lastUpdated": "2026-08-18T08:30:04.123Z"
}
```

---

### A.4 `GET /instruments/resolve`

**Full query string:** `?exchange=NSE&symbol=SBIN-EQ`

**Response (found):**
```json
{
  "found": true,
  "instrument": {
    "token": "3045",
    "symbol": "SBIN-EQ",
    "name": "SBIN",
    "expiry": "",
    "strike": "-1.000000",
    "lotsize": "1",
    "instrumenttype": "",
    "exchSeg": "NSE",
    "tickSize": "5.000000"
  }
}
```
**Response (not found):**
```json
{"found": false, "instrument": null}
```

---

### A.5 `GET /instruments/token`

**Full query string:** `?exchange=NSE&token=3045`

**Response:** identical shape to A.4.

---

### A.6 `GET /instruments/search`

**Full query string:** `?query=SBIN&exchange=NSE&limit=10` (`exchange` optional, `limit` optional — defaults to 20, capped at 200)

**Response:**
```json
{
  "count": 4,
  "results": [
    {"token": "3045", "symbol": "SBIN-EQ", "name": "SBIN", "expiry": "", "strike": "-1.000000", "lotsize": "1", "instrumenttype": "", "exchSeg": "NSE", "tickSize": "5.000000"},
    {"token": "11128", "symbol": "SBIN-AF", "name": "SBIN", "expiry": "", "strike": "-1.000000", "lotsize": "1", "instrumenttype": "", "exchSeg": "NSE", "tickSize": "5.000000"},
    {"token": "4884", "symbol": "SBIN-BE", "name": "SBIN", "expiry": "", "strike": "-1.000000", "lotsize": "1", "instrumenttype": "", "exchSeg": "NSE", "tickSize": "5.000000"},
    {"token": "12740", "symbol": "SBIN-BL", "name": "SBIN", "expiry": "", "strike": "-1.000000", "lotsize": "1", "instrumenttype": "", "exchSeg": "NSE", "tickSize": "5.000000"}
  ]
}
```

---

### A.7 `POST /internal/instruments/refresh`

No body.

**Response (success):** same shape as A.3, with a fresh `lastUpdated` and `source: "live"`.
**Response (failure):** `500` or `502` depending on failure type (see section 4.5) — the
in-memory index is untouched either way.

---

### A.8 `GET /market/quote`

**Full query string — by token:** `?mode=FULL&exchange=NSE&tokens=3045,881`
**Full query string — by symbol:** `?mode=FULL&exchange=NSE&symbols=SBIN-EQ,RELIANCE-EQ`
(`mode` is `LTP`, `OHLC`, or `FULL`. If both `tokens` and `symbols` given, `tokens` wins.)

**Response (`mode=FULL`):**
```json
{
  "status": true, "message": "SUCCESS", "errorcode": "",
  "data": {
    "fetched": [
      {
        "exchange": "NSE", "tradingSymbol": "SBIN-EQ", "symbolToken": "3045",
        "ltp": 568.2, "open": 567.4, "high": 569.35, "low": 566.1, "close": 567.4,
        "lastTradeQty": 1,
        "exchFeedTime": "21-Jun-2026 10:46:10", "exchTradeTime": "21-Jun-2026 10:46:09",
        "netChange": 0.8, "percentChange": 0.14, "avgPrice": 567.83,
        "tradeVolume": 3556150, "opnInterest": 0,
        "lowerCircuit": 510.7, "upperCircuit": 624.1,
        "totBuyQuan": 839549, "totSellQuan": 1284767,
        "52WeekLow": 430.7, "52WeekHigh": 629.55,
        "depth": {
          "buy": [{"price": 568.2, "quantity": 511, "orders": 2}, {"price": 568.15, "quantity": 411, "orders": 2}],
          "sell": [{"price": 568.25, "quantity": 348, "orders": 5}]
        }
      }
    ],
    "unfetched": []
  }
}
```
`mode=LTP` returns only `{exchange, tradingSymbol, symbolToken, ltp}` per instrument.
`mode=OHLC` adds `open, high, low, close` but omits everything else above.

**Response (unresolvable symbol — this service's own 400, never reaches AngelOne):**
```json
{"status": false, "message": "No instrument found for exchange=NSE symbol=NOT-A-REAL-SYMBOL — check spelling/exchange, or try GET /instruments/search?query=NOT-A-REAL-SYMBOL"}
```

---

### A.9 `GET /market/candles`

**Full query string — by token:** `?exchange=NSE&symbolToken=3045&interval=ONE_DAY&fromDate=2026-08-01 09:15&toDate=2026-08-14 15:30`
**Full query string — by symbol:** `?exchange=NSE&symbol=SBIN-EQ&interval=ONE_DAY&fromDate=2026-08-01 09:15&toDate=2026-08-14 15:30`
(If both given, `symbolToken` wins. `interval` is one of `ONE_MINUTE`, `THREE_MINUTE`,
`FIVE_MINUTE`, `TEN_MINUTE`, `FIFTEEN_MINUTE`, `THIRTY_MINUTE`, `ONE_HOUR`, `ONE_DAY`.)

**Response:**
```json
{
  "status": true, "message": "SUCCESS", "errorcode": "",
  "data": [
    ["2026-08-06T11:15:00+05:30", 19571.2, 19573.35, 19534.4, 19552.05, 0]
  ]
}
```
Each row: `[timestamp, open, high, low, close, volume]`.

---

### A.10 `GET /market/greeks`

**Full query string:** `?name=TCS&expiryDate=25JAN2026`

**Response:**
```json
{
  "status": true, "message": "SUCCESS", "errorcode": "",
  "data": [
    {
      "name": "TCS", "expiry": "25JAN2026", "strikePrice": "3900.000000", "optionType": "CE",
      "delta": "0.492400", "gamma": "0.028800", "theta": "-4.091800", "vega": "2.296700",
      "impliedVolatility": "16.330000", "tradeVolume": "24048.000000"
    },
    {
      "name": "TCS", "expiry": "25JAN2026", "strikePrice": "4000.000000", "optionType": "CE",
      "delta": "0.239000", "gamma": "0.022200", "theta": "-3.033500", "vega": "1.785400",
      "impliedVolatility": "22.190000", "tradeVolume": "12976.000000"
    }
  ]
}
```
One object per strike price — the array can be long for a busy underlying.

---

### A.11 `POST /market/brokerage`

**Full request body — token given explicitly:**
```json
{
  "orders": [
    {
      "productType": "DELIVERY",
      "transactionType": "BUY",
      "quantity": "10",
      "price": "800",
      "exchange": "NSE",
      "symbolName": "SBIN-EQ",
      "token": "3045"
    }
  ]
}
```

**Full request body — no token, resolved from symbolName:**
```json
{
  "orders": [
    {
      "productType": "DELIVERY",
      "transactionType": "BUY",
      "quantity": "10",
      "price": "800",
      "exchange": "NSE",
      "symbolName": "SBIN-EQ"
    }
  ]
}
```

**Full request body — multi-order basket, mixed (one with token, one without):**
```json
{
  "orders": [
    {
      "productType": "DELIVERY",
      "transactionType": "BUY",
      "quantity": "10",
      "price": "800",
      "exchange": "NSE",
      "symbolName": "SBIN-EQ",
      "token": "3045"
    },
    {
      "productType": "DELIVERY",
      "transactionType": "BUY",
      "quantity": "10",
      "price": "800",
      "exchange": "BSE",
      "symbolName": "PICLL151223"
    }
  ]
}
```

**Response:**
```json
{
  "status": true, "message": "SUCCESS", "errorcode": "",
  "data": {
    "summary": {
      "total_charges": 3.0796,
      "trade_value": 16000,
      "breakup": [
        {"name": "Angel One Brokerage", "amount": 0.0, "msg": "", "breakup": []},
        {
          "name": "External Charges", "amount": 2.976, "msg": "",
          "breakup": [
            {"name": "Exchange Transaction Charges", "amount": 0.56, "msg": "", "breakup": []},
            {"name": "Stamp Duty", "amount": 2.4, "msg": "", "breakup": []},
            {"name": "SEBI Fees", "amount": 0.016, "msg": "", "breakup": []}
          ]
        },
        {
          "name": "Taxes", "amount": 0.1036, "msg": "",
          "breakup": [
            {"name": "Security Transaction Tax", "amount": 0.0, "msg": "", "breakup": []},
            {"name": "GST", "amount": 0.1036, "msg": "", "breakup": []}
          ]
        }
      ]
    },
    "charges": [
      {
        "total_charges": 1.5162,
        "trade_value": 8000,
        "breakup": {"name": "Angel One Brokerage", "amount": 0.0, "msg": "", "breakup": []},
        "External_Charges": {
          "name": "External Charges", "amount": 2.976, "msg": "",
          "breakup": [
            {"name": "Exchange Transaction Charges", "amount": 0.56, "msg": "", "breakup": []},
            {"name": "Stamp Duty", "amount": 2.4, "msg": "", "breakup": []},
            {"name": "SEBI Fees", "amount": 0.016, "msg": "", "breakup": []}
          ]
        },
        "Taxes": {
          "name": "Taxes", "amount": 0.1036, "msg": "",
          "breakup": [
            {"name": "Security Transaction Tax", "amount": 0.0, "msg": "", "breakup": []},
            {"name": "GST", "amount": 0.1036, "msg": "", "breakup": []}
          ]
        }
      }
    ]
  }
}
```
(`data.charges` has one entry per order in the request — shown here with one for brevity;
a two-order request produces two entries, mirroring AngelOne's own documented shape exactly.)

**Response — unresolvable symbol, no token given (this service's own 400):**
```json
{"status": false, "message": "No instrument found for exchange=NSE symbol=NOT-A-REAL-SYMBOL — check spelling/exchange, or try GET /instruments/search?query=NOT-A-REAL-SYMBOL"}
```

---

### A.12 `POST /market/margin`

**Full request body — token given explicitly:**
```json
{
  "positions": [
    {
      "exchange": "NFO",
      "qty": 50,
      "price": 0,
      "productType": "INTRADAY",
      "token": "67300",
      "tradeType": "BUY",
      "orderType": "MARKET"
    },
    {
      "exchange": "NFO",
      "qty": 50,
      "price": 0,
      "productType": "INTRADAY",
      "token": "67308",
      "tradeType": "SELL",
      "orderType": "LIMIT"
    }
  ]
}
```

**Full request body — no token, resolved from symbol:**
```json
{
  "positions": [
    {
      "exchange": "NFO",
      "qty": 50,
      "price": 0,
      "productType": "INTRADAY",
      "symbol": "NIFTY28AUG25FUT",
      "tradeType": "BUY",
      "orderType": "MARKET"
    }
  ]
}
```

**Response:**
```json
{
  "status": true, "message": "SUCCESS", "errorcode": "",
  "data": {
    "totalMarginRequired": 29612.35,
    "marginComponents": {
      "netPremium": 5060,
      "spanMargin": 0,
      "marginBenefit": 79876.5,
      "deliveryMargin": 0,
      "nonNFOMargin": 0,
      "totOptionsPremium": 10100
    }
  }
}
```
See section 10's verified finding: `marginComponents` frequently comes back all-zero
while `totalMarginRequired` is still populated — that's AngelOne's own known
intermittent behavior, not a bug in this service.

**Response — unresolvable symbol, no token given (this service's own 400):**
```json
{"status": false, "message": "No instrument found for exchange=NFO symbol=NOT-A-REAL-SYMBOL — check spelling/exchange, or try GET /instruments/search?query=NOT-A-REAL-SYMBOL"}
```

---

### A.13 `WS /ws/feed`

Not REST — plain WebSocket text frames, JSON in both directions.

**Subscribe message (client → server):**
```json
{"action": "subscribe", "exchangeType": 1, "token": "3045", "mode": 1}
```
`exchangeType`: `1` (NSE CM), `2` (NSE FO), `3` (BSE CM), `4` (BSE FO), `5` (MCX FO).
`mode`: `1` (LTP), `2` (Quote), `3` (Snap Quote).

**Unsubscribe message (client → server):**
```json
{"action": "unsubscribe", "exchangeType": 1, "token": "3045", "mode": 1}
```

**Tick message (server → client):**
```json
{
  "type": "tick",
  "tick": {
    "subscriptionMode": 1,
    "exchangeType": 1,
    "token": "3045",
    "exchangeTimestamp": 1786757107544,
    "ltp": 1067.7
  }
}
```
Higher `mode` values (`2`/`3`) include more fields per AngelOne's binary payload spec —
this service parses and re-serializes them as JSON, field set grows with subscription mode.

**Error message (server → client, malformed input):**
```json
{"type": "error", "message": "malformed request: ..."}
```

---

### A.14 `GET /market/oi`

**Full query string — by token:** `?exchange=NFO&symbolToken=46823&interval=THREE_MINUTE&fromDate=2026-08-06 11:15&toDate=2026-08-06 12:00`
**Full query string — by symbol:** `?exchange=NFO&symbol=NIFTY28AUG25FUT&interval=THREE_MINUTE&fromDate=2026-08-06 11:15&toDate=2026-08-06 12:00`
(If both given, `symbolToken` wins. Same `interval` values as `/market/candles`.)

**Response:**
```json
{"status": true, "message": "SUCCESS", "errorcode": "",
 "data": [{"time": "2026-08-06T11:15:00+05:30", "oi": 1661100}]}
```

**Response (unresolvable symbol — this service's own 400):**
```json
{"status": false, "message": "No instrument found for exchange=NFO symbol=NOT-A-REAL-SYMBOL — check spelling/exchange, or try GET /instruments/search?query=NOT-A-REAL-SYMBOL"}
```

---

### A.15 `GET /market/intraday-eligible`

**Full query string:** `?exchange=NSE` (or `?exchange=BSE`)

**Response:**
```json
{"status": true, "message": "SUCCESS", "errorcode": "",
 "data": [
   {"exchange": "NSE", "SymbolName": "CHEMPLASTS", "Multiplier": "5.0"},
   {"exchange": "NSE", "SymbolName": "SANGHVIMOV", "Multiplier": "5.0"}
 ]}
```

**Response (invalid exchange — this service's own 400):**
```json
{"status": false, "message": "exchange must be NSE or BSE for /market/intraday-eligible, got: NFO"}
```

---

### A.16 `GET /market/cautionary`

No params, no body.

**Response:**
```json
{"status": true, "message": "SUCCESS", "errorcode": "",
 "data": [
   {"token": "532083", "symbol": "SHKALYN", "message": "Security is under Gross settlement (Trade for Trade)"},
   {"token": "512477", "symbol": "BETXIND", "message": "Stage 1 Long Term ASM scrip. ..."}
 ]}
```
