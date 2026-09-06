# Stock-Market-Backend ↔ angelone-market-service — Full Integration Guide

This document explains **everything** about how your two Spring Boot services fit together:
what's already built, whether the integration is actually correct, how the entire user
journey works end-to-end, and exactly how to run and test all of it yourself.

I read every file in both projects (not just skimmed) to write this — code paths,
config, migrations, and the existing README/API_TESTING.md — so this is grounded in
your actual code, not a generic explanation.

---

## 1. Verdict: is the integration okay?

**Yes — it's correctly wired and unusually well-documented already.** I verified the
specific things that most commonly break this kind of two-service setup:

| Check | Result |
|---|---|
| `INTERNAL_API_KEY` identical in both `.env` files | ✅ Matches (64-char value, both files) |
| Backend's `ANGEL_ONE_BASE_URL` (`http://localhost:8081`) matches market-service's `server.port` (`8081`) | ✅ Matches |
| Backend's `ANGEL_ONE_WS_URL` (`ws://localhost:8081/ws/feed`) matches market-service's `/ws/feed` handler | ✅ Matches |
| Every backend proxy controller injects `AngelOneClient`, which auto-attaches `X-Internal-Api-Key` | ✅ Correct, centralized in `AngelOneConfig` |
| Market-service's `InternalApiKeyFilter` rejects any request without that header (except `/ws/**`) | ✅ Correct, and correctly exempts the public WebSocket |
| Backend maps upstream failures (service down / 4xx / 5xx) to clean `502`/`503` via `AngelOneServiceException` + `GlobalExceptionHandler` | ✅ Correct |
| Market-service maps its own AngelOne-upstream errors via its own `GlobalExceptionHandler` | ✅ Present |
| Both services can be started independently — backend degrades gracefully (market endpoints 503, everything else fine) if market-service isn't up | ✅ By design, confirmed in code |

The **one gap** I found: neither project has any custom test classes beyond the
default Spring Boot "context loads" smoke test (`TradingApplicationTests.java`,
`AngeloneMarketServiceApplicationTests.java`). There is a very good manual `curl`
walkthrough (`Stock-Market-Backend/API_TESTING.md`), but no automated unit/integration
tests. Section 8 below shows you how to add real ones.

Everything else — architecture, security boundary, session lifecycle, error handling —
is solid, so the rest of this document is about **understanding and exercising** what's
there, not fixing bugs.

---

## 2. The big picture: why two services at all?

```
┌─────────────────────────────┐
│  Client (browser / mobile)  │
└──────────────┬───────────────┘
               │  Authorization: Bearer <JWT>
               ▼
┌───────────────────────────────────────┐        ┌──────────────────────────────┐
│   Stock-Market-Backend   :8080         │        │        PostgreSQL :5432      │
│   - /auth   (register/login/refresh)   │◄──────►│  users, refresh_tokens,      │
│   - /otp    (phone/email verification) │        │  otp_verifications           │
│   - /users  (profile)                  │        └──────────────────────────────┘
│   - /admin  (user mgmt + AngelOne mgmt)│
│   - /market, /instruments  (PROXY) ────┼──── X-Internal-Api-Key ────┐
└─────────────────────────────────────────┘                          │
                                                                       ▼
                                                    ┌──────────────────────────────────┐
                                                    │  angelone-market-service  :8081   │
                                                    │  - ONE shared AngelOne session    │
                                                    │  - instrument index (in-memory)   │
                                                    │  - live tick WebSocket            │
                                                    │  - caches (quote/candle/greeks..) │
                                                    └───────────────┬────────────────────┘
                                                                    │
                                                                    ▼
                                                    ┌──────────────────────────────────┐
                                                    │      AngelOne SmartAPI (external) │
                                                    └──────────────────────────────────┘
```

**Why not just call AngelOne directly from the backend, or let every user log into
AngelOne themselves?** Two reasons, both encoded directly in the code comments:

1. **AngelOne allows very few concurrent sessions per account.** If every user of your
   app logged into AngelOne individually, you'd hit that limit almost immediately, and
   you'd need every user to *have* their own AngelOne trading account just to see a
   stock quote. Instead, `angelone-market-service` logs in **once**, with **your**
   account, and re-broadcasts the data to everyone.
2. **Clean separation of concerns.** `Stock-Market-Backend` owns everything about *your*
   users (identity, passwords, JWTs, roles). `angelone-market-service` knows nothing
   about your users at all — it only knows "is this caller allowed to ask me for market
   data" (via the shared internal API key). This means a bug or breach in one system's
   auth can't cascade into the other.

---

## 3. How the two services actually talk to each other

This is the part you asked about specifically, so here's the full mechanics.

### 3.1 The shared secret

Both services read the **same value** from their own `.env` file under the key
`INTERNAL_API_KEY`. It is not a JWT, not per-user, not time-limited — it's a static
shared secret, generated once with:

```bash
openssl rand -hex 32
```

- In `Stock-Market-Backend/.env` → becomes `angelone.internal-api-key` (via
  `application.yml`'s `${INTERNAL_API_KEY}` placeholder) → bound into the
  `AngelOneProperties` record.
- In `angelone-market-service/.env` → becomes `internal.api-key` → bound into the
  `InternalProperties` record.

**These two values must be byte-for-byte identical**, or every proxied call will get a
`401` from the market-service. I confirmed they currently match in your uploaded zip.

### 3.2 Backend → market-service (outbound side)

`AngelOneConfig` builds one `RestClient` bean, pre-configured with the base URL and the
header, **once**, at startup:

```java
@Bean
public RestClient angelOneRestClient(AngelOneProperties props) {
    return RestClient.builder()
            .baseUrl(props.baseUrl())                                  // http://localhost:8081
            .defaultHeader("X-Internal-Api-Key", props.internalApiKey())
            .defaultHeader("Content-Type", "application/json")
            .defaultHeader("Accept", "application/json")
            .build();
}
```

Every proxy controller (`MarketController`, `InstrumentProxyController`,
`AngelOneAdminController`) then injects the thin wrapper `AngelOneClient`, which uses
that pre-configured `RestClient` for every GET/POST — so the header is attached
automatically, on every single call, with zero chance of a controller forgetting it.

`AngelOneClient` also standardizes error handling for *every* proxied call in one place:

| Failure | What happens | Result to your frontend |
|---|---|---|
| Market-service process isn't running / connection refused | `ResourceAccessException` caught | `AngelOneServiceException` → **503** |
| Market-service returns 4xx (bad params, e.g. unknown symbol) | `onStatus(is4xxClientError)` | `AngelOneServiceException` → **502** (message passed through) |
| Market-service returns 5xx (its own AngelOne call failed) | `onStatus(is5xxServerError)` | `AngelOneServiceException` → **502** |

`GlobalExceptionHandler` in the backend then turns any `AngelOneServiceException` into
a clean, consistent `ApiResponse` with the right HTTP status — your frontend never sees
a raw Spring stack trace.

### 3.3 market-service (inbound side)

`InternalApiKeyFilter` is a `OncePerRequestFilter` that runs on **every** request except
`/ws/**`:

```java
if (request.getRequestURI().startsWith("/ws/")) {
    filterChain.doFilter(request, response);   // public WebSocket — no key required
    return;
}
String provided = request.getHeader("X-Internal-Api-Key");
if (provided == null || !provided.equals(internalProperties.apiKey())) {
    response.setStatus(401);
    ...
    return;
}
```

This is intentionally simple — a single shared secret, not JWT/OAuth — because this
service has exactly **one legitimate caller** (your backend), never talks to real user
credentials, and is not meant to be reachable directly by browsers. The `/ws/feed`
WebSocket is deliberately excluded because it's meant to be reachable either directly
by a frontend or proxied through the backend, depending on how you deploy it — it has
its own CORS-based origin allow-list instead (`feed.allowed-origins`).

### 3.4 What actually flows through the proxy

Two different response shapes get normalized:

- **Market data / instrument endpoints** (`/market/**`, `/instruments/**`): AngelOne
  itself (and this service, mirroring it) returns an envelope
  `{status, message, errorcode, data}`. The backend's `MarketController` unwraps
  `response.get("data")` and re-wraps it in the backend's own standard
  `ApiResponse<T>` shape, so every endpoint in your app — auth, user, market, whatever —
  has one consistent response contract for your frontend to parse.
- **Admin/session endpoints** (`/admin/angelone/**`): these return plain maps
  (`{loggedIn: true}` etc.), not the AngelOne envelope, so the backend passes the whole
  map through as `data`.

---

## 4. The AngelOne session lifecycle (inside angelone-market-service)

This is the part that's easy to misunderstand, so it's worth walking through in detail
since it's the foundation everything else depends on.

**Core idea:** there is exactly **one** AngelOne login for the entire application,
held in `AngelSessionManager` as an `AtomicReference<TokenData>` (jwtToken,
refreshToken, feedToken). Nothing else in the service is allowed to talk to AngelOne's
login endpoints directly — every other class reads the *current* token from this one
place.

**Sequence at boot:**

1. `StartupLoginRunner` (an `ApplicationRunner`) calls `sessionManager.login()` once, as
   part of application startup.
2. `login()` generates a live 6-digit TOTP code from your static base32 secret
   (`TotpGenerator`, using the same seed your authenticator app was set up with — **not**
   a code you type in manually, it's regenerated fresh every call using the current
   time).
3. It POSTs `{clientcode, password: pin, totp}` to AngelOne's
   `/rest/auth/angelbroking/user/v1/loginByPassword`.
4. On success, the returned `{jwtToken, refreshToken, feedToken}` is stored in the
   `AtomicReference`, and a callback (`onSessionRenewed`) fires — this is what tells the
   feed client to reconnect the WebSocket with the fresh `feedToken`.
5. If login fails at startup (AngelOne briefly down, wrong TOTP secret, etc.), the
   service **does not crash** — it logs the error and stays up. Market-data calls will
   simply fail (mapped to 502/503 by the backend) until a session exists. You can trigger
   a manual retry via `POST /admin/angelone/session/relogin` from the backend, which
   is exactly what that admin endpoint is for.

**Two scheduled jobs keep the session alive after boot:**

| Job | Trigger | Why |
|---|---|---|
| `dailyRelogin()` | Cron `0 45 8 * * *` (8:45 AM IST daily, configurable via `angel.daily-relogin-cron`) | AngelOne sessions hard-expire at midnight regardless of activity, so a fresh full login is forced daily, ahead of market pre-open |
| `scheduledRefresh()` | Every `angel.token-refresh-interval-ms` (default 1,500,000 ms = 25 min) | The JWT itself is shorter-lived than the whole-day session; this proactively exchanges the refresh token for a new JWT+feed token *before* it would naturally expire, so live requests never race an expiry |

If a scheduled refresh fails, it falls back to a full `login()` automatically. If *that*
also fails, it just logs and waits for the next cycle — the feed goes stale but the
service stays up.

**Resilience against a single missed 401:** `AngelRestCaller` (used for every actual
market-data call to AngelOne) catches a `401` on any call, forces one `sessionManager.refresh()`,
and retries **once**. This absorbs the rare case where AngelOne invalidates the token
server-side between scheduled refreshes, without ever surfacing a raw 401 up through the
proxy to your users.

---

## 5. The full user workflow (Stock-Market-Backend)

This is the part most relevant to "user creation and entire workflow." Here's every
step, with the actual code path.

### 5.1 Registration → `POST /auth/register`

```
AuthController.register()
  → RateLimitService.checkRegistrationPerIp()      (Bucket4j — throttles by client IP)
  → AuthService.register(request)
      1. Look up existing user by phone
      2. If phone exists AND already phoneVerified  → 409 DuplicateResourceException
      3. If email given AND belongs to a *different*, verified account → 409
      4. Otherwise: either UPDATE the existing unverified row (resuming an abandoned
         registration) or INSERT a brand-new User row
         - password is BCrypt-hashed via PasswordEncoder before storage
         - phoneVerified = false, emailVerified = false at creation
      5. otpService.generateAndSend(user, OtpType.PHONE)   ← fires immediately
      6. issueTokenPair(user)  → returns {accessToken, refreshToken} right away
```

Two subtle-but-important design choices baked into `AuthService.register`:

- **A user gets a valid access token immediately on registration**, even though they
  can't fully log in yet — this lets the freshly-registered client call `POST /otp/verify`
  right away using that same token, without a chicken-and-egg problem.
- **Abandoned registrations are resumable, not permanently blocking.** If someone starts
  registering, never verifies, and tries again later with the same phone, the code
  updates that existing unverified row instead of throwing a duplicate error — you only
  get a hard `409` if the phone belongs to an account that's already *verified*.

### 5.2 OTP delivery and verification

`OtpService.generateAndSend`:
- Rate-limited per user (`RateLimitService.checkOtpSendPerUser`)
- Generates a 6-digit numeric code, BCrypt-hashes it before storing (the plaintext code
  is never persisted)
- Deletes any prior active OTP of the same type for that user first — only one active
  OTP per (user, type) at a time
- Expires in 5 minutes
- Delivery is pluggable via the `OtpSender` interface. In dev (`otp.sender: logging`,
  the default), `LoggingOtpSender` just **logs the plaintext code** — that's your test
  workflow: register, then read the code out of the application logs. Swap this for a
  real SMS/email provider (e.g. Twilio) later by implementing `OtpSender` and switching
  `otp.sender`.

`OtpService.verify`:
- 404 if no active OTP of that type exists for the user
- Deletes + throws `OtpExpiredException` if past expiry
- Tracks `attempts`; after 5 failed attempts the OTP is invalidated (deleted) —
  `TooManyOtpAttemptsException` (429), forcing a fresh `/otp/send`
- On success: single-use (deleted immediately), and flips `phoneVerified` or
  `emailVerified` on the `User` row. `PASSWORD_RESET`-type OTPs deliberately do **not**
  flip either flag — that type only proves "you still hold this phone right now" for a
  different, security-sensitive flow (changing your password).

### 5.3 Login → `POST /auth/login`

```
AuthService.login(request)
  1. Require phone OR email
  2. Look up user; BadCredentialsException(401) if not found — deliberately the
     same generic message as a wrong password, so login never leaks which
     phone/emails are registered
  3. Reject if !user.isEnabled()             → 401 "Account is disabled"
  4. Reject if password doesn't match (BCrypt)→ 401 "Invalid credentials"
  5. Reject if !user.isPhoneVerified()        → 401 "Phone number not verified —
     verify via /otp/verify before logging in"
  6. issueTokenPair(user)
```

**This is why the two-step register → verify → login flow is enforced, not optional:**
step 5 makes login impossible until the phone OTP has been verified, no matter how
correct the password is.

### 5.4 Token pair mechanics

`issueTokenPair(user)`:
- Access token: a signed JWT (`JwtService.generateAccessToken`) carrying the user's
  UUID as subject, expiring in `jwt.access-token-expiry` seconds (900s = 15 min by
  default).
- Refresh token: a random UUID string (not a JWT), stored server-side in the
  `refresh_tokens` table with an expiry (`jwt.refresh-token-expiry` = 604800s = 7 days
  by default) and a foreign key to the user. This is what makes `/auth/refresh` and
  `/auth/logout` (server-side revocation) possible — you can't revoke a stateless JWT,
  but you can delete a stored refresh-token row.

`POST /auth/refresh`: looks up the refresh token row; if expired, deletes it and 401s;
otherwise mints a brand-new access token for that user and returns the **same** refresh
token back (it is not rotated on every refresh in this implementation).

`POST /auth/logout`: simply deletes the matching refresh-token row if present — this
is what actually invalidates a session server-side (the still-valid access token will
keep working until it naturally expires at most 15 minutes later, but no new access
token can be minted from that refresh token again).

### 5.5 How every subsequent authenticated request is validated

`JwtFilter` (a `OncePerRequestFilter` registered before
`UsernamePasswordAuthenticationFilter` in `SecurityConfig`):

1. No `Authorization: Bearer ...` header → just pass through; Spring Security's own
   401 handling takes over downstream if the route required auth.
2. Extract user UUID from the JWT (`JwtService.extractUserId`) — this both verifies the
   signature and decodes the claim in one call.
3. Load the `User` from the DB; if found and `isEnabled()`, build a Spring Security
   `UsernamePasswordAuthenticationToken` with the user's `authorities()` (their `Role`,
   e.g. `ROLE_USER` / `ROLE_ADMIN`) and set it on the `SecurityContextHolder`.
4. Any exception (expired/invalid signature) is swallowed intentionally — no auth gets
   set, and Spring Security's normal 401 handling takes it from there.

`SecurityConfig` route rules:

```java
.requestMatchers("/auth/**").permitAll()            // register/login/refresh/logout
.requestMatchers("/market/**").authenticated()       // any logged-in user (USER or ADMIN)
.requestMatchers("/instruments/**").authenticated()  // any logged-in user
.requestMatchers("/admin/**").hasRole("ADMIN")       // admin only — @PreAuthorize double-guards this too
.anyRequest().authenticated()                        // everything else (incl. /otp, /users)
```

Note `/admin/**` is double-guarded — both the route matcher above **and**
`@PreAuthorize("hasRole('ADMIN')")` on `AdminController` / `AngelOneAdminController`
directly — so a single misconfiguration in one layer doesn't expose admin endpoints.

### 5.6 Admin bootstrap

On every startup, `AdminSeeder` checks if any `ADMIN`-role user exists; if not, it
creates exactly one from `admin.seed.*` properties (`ADMIN_SEED_PHONE`,
`ADMIN_SEED_PASSWORD`, etc. from `.env`). This seeded account is created with
`phoneVerified = true` directly (it skips `/auth/register`, so there's no OTP step to
complete) — it can log in immediately with the seeded phone + password. Change that
password via the app the moment you've confirmed things work.

### 5.7 End-to-end flow diagram

```
 Client                     Backend :8080                          DB
   │  POST /auth/register       │
   ├────────────────────────────►│ create User(unverified)          │
   │                             ├──────────────────────────────────►│
   │                             │ generate+store OTP (hashed)       │
   │                             ├──────────────────────────────────►│
   │  201 {accessToken, ...}     │ (OTP logged to console in dev)    │
   │◄────────────────────────────┤                                   │
   │                             │                                   │
   │  POST /otp/verify (Bearer)  │                                   │
   ├────────────────────────────►│ check code, flip phoneVerified    │
   │  200 OK                     ├──────────────────────────────────►│
   │◄────────────────────────────┤                                   │
   │                             │                                   │
   │  POST /auth/login           │                                   │
   ├────────────────────────────►│ verify pwd + phoneVerified==true  │
   │  200 {accessToken,          │ issue new access+refresh tokens   │
   │       refreshToken}         ├──────────────────────────────────►│
   │◄────────────────────────────┤                                   │
   │                             │                                   │
   │  GET /market/quote (Bearer) │                                   │
   ├────────────────────────────►│──── X-Internal-Api-Key ──► angelone-market-service :8081
   │  200 {data: {...}}          │◄──── {status,data,...} ────┤
   │◄────────────────────────────┤                                   │
```

---

## 6. Market-data request lifecycle (the proxy in action)

Concretely, here's what happens on `GET /market/quote?mode=LTP&exchange=NSE&symbols=SBIN-EQ`:

1. **Backend** (`MarketController.quote`): JWT already validated by `JwtFilter`
   upstream. Builds a param map, calls `angelOneClient.get("/market/quote", params, MAP_TYPE)`.
2. **AngelOneClient**: issues the GET against `http://localhost:8081/market/quote`
   with `X-Internal-Api-Key` attached automatically.
3. **Market-service** (`InternalApiKeyFilter`): validates the header, passes through.
4. **Market-service** (`MarketDataController` → `MarketDataService`): checks its
   Caffeine cache first (`cache.quote-ttl-ms` = 1000ms — quotes are cached for exactly
   1 second, since they change constantly but a burst of near-simultaneous requests for
   the same symbol shouldn't each hit AngelOne). Cache miss → calls `AngelRestCaller`,
   which attaches the *current* AngelOne session JWT (`AngelHeaders.forAuthenticated`)
   and calls AngelOne's real quote endpoint. Result is cached, then returned.
5. **Backend**: unwraps `response.get("data")`, wraps in `ApiResponse.success(...)`,
   returns `200` to the client.

If step 4 hits a `401` from AngelOne (rare — session expired between refresh cycles),
`AngelRestCaller` forces one `sessionManager.refresh()` and retries transparently — the
client at step 1 never sees this happen.

If the market-service process isn't running at all, step 2 fails at the TCP level
(`ResourceAccessException`), and the client gets a clean `503 Service Unavailable`
with the message *"Angel One market service is currently unavailable — please try
again shortly."* — never a raw connection-refused stack trace.

Different endpoints have different cache TTLs, all in `angelone-market-service/application.yml`
under `cache.*` — quotes 1s, candles 30s–1h depending on interval, greeks 60s,
brokerage/margin 5min, intraday-eligible list 6h, cautionary list 1h. These are tuned
per how fast each type of data actually changes.

---

## 7. Configuration reference

### 7.1 Stock-Market-Backend `.env`

| Variable | Required | Purpose |
|---|---|---|
| `JWT_SECRET` | ✅ | Signs/verifies access tokens (min 64 chars) |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | ✅ | Postgres connection |
| `ADMIN_SEED_PHONE`, `ADMIN_SEED_PASSWORD`, `ADMIN_SEED_FIRST_NAME`, `ADMIN_SEED_LAST_NAME`, `ADMIN_SEED_EMAIL` | phone+password ✅, rest optional | First-boot admin bootstrap |
| `INTERNAL_API_KEY` | ✅ | **Must match** the same variable in `angelone-market-service/.env` |
| `ANGEL_ONE_BASE_URL` | optional (defaults `http://localhost:8081`) | Where the backend sends proxied calls |
| `ANGEL_ONE_WS_URL` | optional (defaults `ws://localhost:8081/ws/feed`) | Reference for the live feed URL (not proxied by the backend today — see §9) |

### 7.2 angelone-market-service `.env`

| Variable | Required | Purpose |
|---|---|---|
| `ANGEL_CLIENT_CODE`, `ANGEL_PIN` | ✅ | Your **one** AngelOne account's login + trading PIN |
| `ANGEL_TOTP_SECRET` | ✅ | Base32 TOTP seed from AngelOne's SmartAPI portal (2FA setup) — not a live 6-digit code |
| `ANGEL_API_KEY` | ✅ | SmartAPI key (sent as `X-PrivateKey`) |
| `ANGEL_LOCAL_IP`, `ANGEL_PUBLIC_IP`, `ANGEL_MAC_ADDRESS` | optional, defaulted | Required headers per SmartAPI docs; defaults are fine for local dev |
| `INTERNAL_API_KEY` | ✅ | **Must match** the backend's value |
| `FEED_ALLOWED_ORIGINS` | optional | CORS allow-list for direct browser connections to `/ws/feed` |

**Generating the shared key** (run once, paste the same value into both files):

```bash
openssl rand -hex 32
```

---

## 8. Setup, running, and testing — step by step

### 8.1 Prerequisites

- Java 21
- Maven (the `./mvnw` wrapper is included in both projects — no separate Maven install needed)
- Docker & Docker Compose (for Postgres)
- A real AngelOne SmartAPI account with TOTP-based 2FA already enabled (needed for
  `angelone-market-service` to log in — without it, market-data endpoints will 503, but
  auth/user/admin-user endpoints work fine on their own)

### 8.2 First-time setup

```bash
# 1. angelone-market-service
cd angelone-market-service
cp .env.example .env
# fill in ANGEL_CLIENT_CODE, ANGEL_PIN, ANGEL_TOTP_SECRET, ANGEL_API_KEY
# generate and set INTERNAL_API_KEY:
openssl rand -hex 32   # paste result as INTERNAL_API_KEY here

# 2. Stock-Market-Backend
cd ../Stock-Market-Backend
cp .env.example .env
# set JWT_SECRET (any long random string, 64+ chars)
# paste the SAME INTERNAL_API_KEY value from step 1 here
```

### 8.3 Startup order (matters)

```bash
# Terminal 1 — Postgres
cd Stock-Market-Backend
docker compose up -d

# Terminal 2 — Angel One market-data service (port 8081)
cd angelone-market-service
./mvnw spring-boot:run
# Wait for a log line indicating a successful AngelOne login

# Terminal 3 — main backend (port 8080)
cd Stock-Market-Backend
./mvnw spring-boot:run
# Wait for: "Bootstrapped first ADMIN account with phone ..." (first boot only)
```

The backend is resilient to start-order mistakes: it can start before the market-service
is up. Auth/user/admin-user endpoints work immediately; `/market/**` and
`/instruments/**` will return `503` until the market-service is reachable and logged in.

### 8.4 Full manual test walkthrough — user creation → verified login

This mirrors your existing `Stock-Market-Backend/API_TESTING.md`, condensed to the core
user-lifecycle path. Run top to bottom in one shell session:

```bash
export BASE=http://localhost:8080

# 1. Register
curl -s -X POST $BASE/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"firstName":"Abhi","lastName":"Sharma","phone":"+919876543210","password":"secret123","email":"abhi@example.com"}'
# → 201, capture data.accessToken as USER_TOKEN

export USER_TOKEN="<paste accessToken>"

# 2. Confirm login is blocked pre-verification (expect 401)
curl -s -X POST $BASE/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"phone":"+919876543210","password":"secret123"}'

# 3. Read the OTP from the backend's console log (LoggingOtpSender prints it — dev only)
#    Look for a line like: "[DEV-ONLY OTP...] type=PHONE ... code=123456"
export PHONE_OTP="123456"

# 4. Verify
curl -s -X POST $BASE/otp/verify \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $USER_TOKEN" \
  -d "{\"type\":\"PHONE\",\"code\":\"$PHONE_OTP\"}"
# → 200

# 5. Login for real
curl -s -X POST $BASE/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"phone":"+919876543210","password":"secret123"}'
# → 200, {accessToken, refreshToken}
export USER_TOKEN="<fresh accessToken>"
export USER_REFRESH="<refreshToken>"

# 6. Fetch your own profile
curl -s $BASE/users/me -H "Authorization: Bearer $USER_TOKEN"

# 7. Refresh + logout (sanity check server-side revocation)
curl -s -X POST $BASE/auth/refresh -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"$USER_REFRESH\"}"
curl -s -X POST $BASE/auth/logout -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"$USER_REFRESH\"}"
```

### 8.5 Testing the AngelOne integration specifically

```bash
# Log in as the seeded admin (phone/password from your .env ADMIN_SEED_*)
curl -s -X POST $BASE/auth/login -H 'Content-Type: application/json' \
  -d '{"phone":"+910000000001","password":"ChangeMe@123"}'
export ADMIN_TOKEN="<accessToken>"

# Is the market-service's AngelOne session alive?
curl -s $BASE/admin/angelone/session/status -H "Authorization: Bearer $ADMIN_TOKEN"
# → {"data": {"loggedIn": true}}

# Resolve a symbol to its AngelOne instrument token
curl -s "$BASE/instruments/resolve?exchange=NSE&symbol=SBIN-EQ" \
  -H "Authorization: Bearer $USER_TOKEN"

# Get a live LTP quote
curl -s "$BASE/market/quote?mode=LTP&exchange=NSE&symbols=SBIN-EQ" \
  -H "Authorization: Bearer $USER_TOKEN"

# Force a re-login (break-glass) — admin only
curl -s -X POST $BASE/admin/angelone/session/relogin -H "Authorization: Bearer $ADMIN_TOKEN"

# Force an instrument index refresh — admin only
curl -s -X POST $BASE/admin/angelone/instruments/refresh -H "Authorization: Bearer $ADMIN_TOKEN"
```

### 8.6 Testing failure modes (proves the error handling actually works)

```bash
# Stop angelone-market-service (Ctrl+C in its terminal), then:
curl -s -w "\n%{http_code}\n" "$BASE/market/quote?mode=LTP&exchange=NSE&symbols=SBIN-EQ" \
  -H "Authorization: Bearer $USER_TOKEN"
# Expect: 503, with a clear "market data service unavailable" message — not a stack trace

# Restart it, then hit an invalid symbol:
curl -s -w "\n%{http_code}\n" "$BASE/instruments/resolve?exchange=NSE&symbol=DOESNOTEXIST" \
  -H "Authorization: Bearer $USER_TOKEN"
# Expect: 200 with {found:false}, since /instruments/resolve is a lookup, not a hard error
```

### 8.7 Adding real automated tests

Right now both projects only have the default Spring Boot context-load smoke test. To
add real coverage, the natural first targets (in priority order) are:

1. **`AuthServiceTest`** (backend, unit test with mocked `UserRepository`/`OtpService`) —
   cover: successful register, duplicate-verified-phone rejection, resuming an abandoned
   unverified registration, login blocked pre-verification, login with wrong password.
2. **`OtpServiceTest`** — cover: correct code passes, wrong code increments attempts,
   5th wrong attempt invalidates the OTP, expired OTP is rejected and deleted.
3. **`AngelOneClientTest`** (backend, using Spring's `MockRestServiceServer` against the
   `angelOneRestClient` bean) — cover: 4xx passthrough, 5xx → `AngelOneServiceException`,
   connection-refused → 503 mapping. This directly tests the exact integration seam
   you're asking about.
4. **`AngelSessionManagerTest`** (market-service) — mock the `RestClient`, verify
   `login()` stores tokens and calls `onSessionRenewed`, verify `refresh()` falls back
   to `login()` when there's no existing session.

A minimal example to get you started (backend, JUnit 5 + Mockito, already on the
classpath via `spring-boot-starter-test`):

```java
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserRepository userRepository;
    @Mock AuthRepository authRepository;
    @Mock JwtService jwtService;
    @Mock JwtProperties jwtProperties;
    @Mock PasswordEncoder passwordEncoder;
    @Mock OtpService otpService;
    @InjectMocks AuthService authService;

    @Test
    void login_rejectsUnverifiedPhone() {
        User user = User.builder()
                .phone("+919876543210")
                .password("hashed")
                .phoneVerified(false)
                .enabled(true)
                .build();

        when(userRepository.findByPhone("+919876543210")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("secret123", "hashed")).thenReturn(true);

        LoginRequest request = new LoginRequest("+919876543210", null, "secret123");

        assertThrows(BadCredentialsException.class, () -> authService.login(request));
    }
}
```

---

## 9. Things worth knowing / deliberate limitations (not bugs)

- **The live tick WebSocket (`/ws/feed`) is not proxied through the backend today.**
  It's exposed directly by `angelone-market-service` on port 8081, protected only by
  CORS origin checking (`feed.allowed-origins`), not JWT. If you want per-user JWT
  auth on the live feed later, that's a deliberate future addition, not something
  currently broken.
- **`otp.sender: logging` is explicitly dev-only** — it logs OTP codes in plaintext to
  the console. Before any real deployment, implement `OtpSender` for a real
  SMS/email provider and switch the `otp.sender` property.
- **The AngelOne session is a single shared resource.** If you ever run multiple
  instances of `angelone-market-service` for horizontal scaling, they'd each try to log
  into the same AngelOne account independently, which will fight over AngelOne's
  concurrent-session limit. That service is currently designed to run as a single
  instance.
- **`/auth/refresh` does not rotate the refresh token** — the same refresh token is
  returned on each refresh, only the access token is new. This is a simpler, valid
  design choice, but if you later want refresh-token rotation (a common hardening
  step), that would be a deliberate change to `AuthService.refresh()`.

---

## 10. Quick file-map cheat sheet

**Stock-Market-Backend** (`com.luffy.trading`):
```
angelone/    AngelOneProperties, AngelOneConfig, AngelOneClient, AngelOneServiceException
market/      MarketController, InstrumentProxyController, AngelOneAdminController
auth/        AuthController, AuthService, JwtFilter, JwtService, RefreshToken
otp/         OtpService, OtpController, LoggingOtpSender, OtpCleanupScheduler
user/        User, UserController, UserService
admin/       AdminController, AdminUserService
config/      SecurityConfig, AdminSeeder, RateLimitService
exception/   GlobalExceptionHandler + custom exceptions
```

**angelone-market-service** (`com.angelone.angelone_market_service`):
```
session/     AngelSessionManager, StartupLoginRunner, TotpGenerator, SessionController
client/      AngelHeaders, AngelRestCaller
marketdata/  MarketDataController, MarketDataService
instrument/  InstrumentService, InstrumentController, StartupInstrumentLoader
feed/        AngelFeedClient, SubscriptionManager, TickParser
broadcast/   PublicFeedWebSocketHandler, WebSocketConfig
config/      InternalApiKeyFilter, InternalProperties, CacheConfig, AppConfig
```

---

## 11. TL;DR — what to actually do next

1. Run the 3-terminal startup in §8.3.
2. Walk through §8.4 once by hand to see the full user lifecycle work.
3. Walk through §8.5 to see the AngelOne proxy actually pull live data.
4. Try §8.6 (kill the market-service) to see the error handling degrade gracefully
   instead of crashing — this is the strongest proof the integration is solid.
5. When you're comfortable, start adding the automated tests from §8.7 — that's the
   one real gap between "this works" and "this is safe to keep changing."
