# Stock Market Backend

A Spring Boot 4.1 backend for a trading platform — user auth, admin management, and a full market-data proxy layer that connects to the Angel One SmartAPI market-data service.

---

## Architecture

```
[Authenticated Client (Browser / Mobile / Scripts)]
           │  JWT  Bearer  token
           ▼
[Stock-Market-Backend  :8080]
  - Auth, OTP, User, Admin
  - /market/**   proxy  ──────────────────────────┐
  - /instruments/** proxy                         │  X-Internal-Api-Key
           │                                      ▼
           │                        [angelone-market-service  :8081]
           │                          - AngelOne SmartAPI session
           │                          - In-memory instrument index
           │                          - Live tick WebSocket feed
           │                                      │
           │                                      ▼
           │                          [AngelOne SmartAPI (external)]
           │                            - loginByPassword / generateTokens
           │                            - Market quote / candles / OI / ...
           │                            - WebSocket tick stream
           │
           ▼
     [PostgreSQL :5432]
       - users / refresh_tokens / otp_verifications
```

**Why two services?** The Angel One service holds exactly one AngelOne session for the whole application (SmartAPI allows very few concurrent sessions). Every user request is served from that single shared session. The backend handles all user identity; the Angel One service knows nothing about per-user auth.

---

## Tech Stack

| Layer            | Technology                     |
| ---------------- | ------------------------------ |
| Language         | Java 21                        |
| Framework        | Spring Boot 4.1                |
| Security         | Spring Security + JWT (JJWT)   |
| Database         | PostgreSQL 17                  |
| Migrations       | Flyway                         |
| Rate Limiting    | Bucket4j                       |
| Containerisation | Docker & Docker Compose        |
| Build Tool       | Maven (wrapper included)       |
| Market Data      | Angel One SmartAPI (via proxy) |

---

## Prerequisites

- Java 21+
- Maven (or use the included `./mvnw` wrapper)
- Docker & Docker Compose
- The **Angel One market-data service** running on port 8081 (see `../angelone-market-service/`)

---

## Getting Started

### 1. Clone the repository

```bash
git clone <repository-url>
cd Stock-Market-Backend
```

### 2. Configure environment variables

```bash
cp .env.example .env
```

Edit `.env` and fill in:

| Variable               | Required | Default                     | Description                                    |
| ---------------------- | -------- | --------------------------- | ---------------------------------------------- |
| `JWT_SECRET`           | ✅        | —                           | Base64 secret for JWT signing (min 64 chars)   |
| `DB_URL`               | ✅        | `jdbc:postgresql://localhost:5432/trading` | PostgreSQL connection URL  |
| `DB_USERNAME`          | ✅        | `postgres`                  | Database username                              |
| `DB_PASSWORD`          | ✅        | `postgres`                  | Database password                              |
| `ADMIN_SEED_PHONE`     | ✅        | `+910000000001`             | Phone for the auto-seeded admin account        |
| `ADMIN_SEED_PASSWORD`  | ✅        | `ChangeMe@123`              | Password for the auto-seeded admin account     |
| `ADMIN_SEED_FIRST_NAME`| ❌        | `Admin`                     | Admin first name                               |
| `ADMIN_SEED_LAST_NAME` | ❌        | `User`                      | Admin last name                                |
| `ADMIN_SEED_EMAIL`     | ❌        | _(none)_                    | Admin email (optional)                         |
| `INTERNAL_API_KEY`     | ✅        | —                           | **Must match** `INTERNAL_API_KEY` in `angelone-market-service/.env` |
| `ANGEL_ONE_BASE_URL`   | ❌        | `http://localhost:8081`     | Base URL of the Angel One market-data service  |
| `ANGEL_ONE_WS_URL`     | ❌        | `ws://localhost:8081/ws/feed` | WebSocket feed URL of the Angel One service  |

> **INTERNAL_API_KEY** is the shared secret between the backend and the Angel One service. Generate with `openssl rand -hex 32` and set the **exact same value** in both services' `.env` files.

### 3. Start PostgreSQL

```bash
docker compose up -d
```

### 4. Start the Angel One market-data service

```bash
cd ../angelone-market-service
./mvnw spring-boot:run
```

Wait for the log line:
```
AngelOne login succeeded
```

### 5. Start this backend

```bash
cd ../Stock-Market-Backend
./mvnw spring-boot:run
```

On first boot, watch for:
```
Bootstrapped first ADMIN account with phone +910000000001 — change its password via the app as soon as possible.
```

The backend starts on **http://localhost:8080**.

---

## Startup Order

```
1. docker compose up -d                  (PostgreSQL)
2. cd angelone-market-service && ./mvnw spring-boot:run   (Angel One service — port 8081)
3. cd Stock-Market-Backend && ./mvnw spring-boot:run      (Backend — port 8080)
```

The backend can start before the Angel One service — market-data endpoints will return 503 until the service is up, while all auth/user/admin endpoints continue to work normally.

---

## API Endpoints

### Auth (public — no JWT required)

| Method | Endpoint         | Description                                      |
| ------ | ---------------- | ------------------------------------------------ |
| POST   | `/auth/register` | Register a new user (triggers phone OTP)         |
| POST   | `/auth/login`    | Login with phone/email + password (post-verification only) |
| POST   | `/auth/refresh`  | Exchange refresh token for a new access token    |
| POST   | `/auth/logout`   | Revoke a refresh token                           |

### OTP (requires JWT)

| Method | Endpoint           | Description                            |
| ------ | ------------------ | -------------------------------------- |
| POST   | `/otp/send`        | Send OTP to phone or email             |
| POST   | `/otp/verify`      | Verify OTP code                        |

### User (requires JWT)

| Method | Endpoint                  | Description                              |
| ------ | ------------------------- | ---------------------------------------- |
| GET    | `/users/me`               | Get your own profile                     |
| PUT    | `/users/me`               | Update your own profile                  |
| POST   | `/users/me/password/otp`  | Request password-change OTP              |
| PUT    | `/users/me/password`      | Change password (OTP + new password)     |

### Market Data (requires JWT — any authenticated user)

All market data is proxied from the Angel One market-data service and cached at the service layer. The `data` field in each response is exactly what AngelOne returns.

| Method | Endpoint                       | Description                                   |
| ------ | ------------------------------ | --------------------------------------------- |
| GET    | `/market/quote`                | Real-time quote (LTP / OHLC / FULL)           |
| GET    | `/market/candles`              | Historical OHLCV candlestick data             |
| GET    | `/market/greeks`               | Option Greeks for an underlying + expiry      |
| POST   | `/market/brokerage`            | Estimate brokerage charges (no order placed)  |
| POST   | `/market/margin`               | Calculate margin required (no order placed)   |
| GET    | `/market/oi`                   | Historical open interest (F&O only)           |
| GET    | `/market/intraday-eligible`    | NSE/BSE intraday eligible scrip list          |
| GET    | `/market/cautionary`           | ASM/GSM cautionary scrips                     |

### Instruments (requires JWT — any authenticated user)

| Method | Endpoint                   | Description                                        |
| ------ | -------------------------- | -------------------------------------------------- |
| GET    | `/instruments/resolve`     | Symbol → instrument details (incl. token)          |
| GET    | `/instruments/token`       | Token → instrument details (reverse lookup)        |
| GET    | `/instruments/search`      | Search by name/symbol (prefix + contains)          |
| GET    | `/instruments/status`      | Index health: loaded, count, age, stale?           |

### Admin — User Management (requires JWT + ADMIN role)

| Method | Endpoint                    | Description                                   |
| ------ | --------------------------- | --------------------------------------------- |
| GET    | `/admin/users`              | List/search users (paginated)                 |
| GET    | `/admin/users/{id}`         | Get a specific user's full profile            |
| PATCH  | `/admin/users/{id}/status`  | Enable/disable a user (can't target self)     |
| POST   | `/admin/users/{id}/promote` | Promote a user to ADMIN                       |

### Admin — Angel One Service Management (requires JWT + ADMIN role)

| Method | Endpoint                             | Description                                            |
| ------ | ------------------------------------ | ------------------------------------------------------ |
| GET    | `/admin/angelone/session/status`     | Check if the Angel One service is logged in            |
| POST   | `/admin/angelone/session/relogin`    | Force a fresh AngelOne login (break-glass)             |
| POST   | `/admin/angelone/instruments/refresh`| Force immediate instrument index refresh               |

---

## Live Tick Feed (WebSocket)

The Angel One service exposes a WebSocket at `ws://localhost:8081/ws/feed` for live price ticks. This is **not** proxied through the backend in the current scope.

**Protocol (client → server)**:
```json
{"action":"subscribe",   "exchangeType":1, "token":"3045", "mode":1}
{"action":"unsubscribe", "exchangeType":1, "token":"3045", "mode":1}
```

**Protocol (server → client)**:
```json
{"type":"tick",  "tick": {"ltp":800.5, "exchangeType":1, "token":"3045", ...}}
{"type":"error", "message":"unknown action: foo"}
```

`exchangeType`: 1=NSE CM, 2=NSE FO, 3=BSE CM, 4=BSE FO, 5=MCX FO  
`mode`: 1=LTP, 2=Quote, 3=SnapQuote (full depth)

To find the `token` for a symbol, use `GET /instruments/resolve?exchange=NSE&symbol=SBIN-EQ` first.

---

## Project Structure

```
src/main/java/com/luffy/trading/
├── TradingApplication.java           # Entry point
├── angelone/                         # Angel One integration client
│   ├── AngelOneProperties.java       # @ConfigurationProperties(prefix="angelone")
│   ├── AngelOneConfig.java           # RestClient bean with pre-set base URL + key
│   ├── AngelOneClient.java           # HTTP proxy client with error handling
│   └── AngelOneServiceException.java # Maps to 502/503 in GlobalExceptionHandler
├── market/                           # Market data + instrument proxy controllers
│   ├── MarketController.java         # /market/** — proxies 8 market-data endpoints
│   ├── InstrumentProxyController.java# /instruments/** — proxies 4 instrument endpoints
│   └── AngelOneAdminController.java  # /admin/angelone/** — session + instrument mgmt
├── auth/                             # JWT login, register, refresh, logout
├── user/                             # User profile management
├── admin/                            # Admin user management
├── otp/                              # OTP send/verify
├── config/                           # SecurityConfig, RateLimitService, AdminSeeder
├── exception/                        # GlobalExceptionHandler + custom exceptions
└── response/                         # ApiResponse<T> wrapper

src/main/resources/
├── application.yml                   # Full config (includes angelone.* properties)
└── db/migration/                     # Flyway V1–V5 SQL migrations
```

---

## Build

```bash
# Compile only
./mvnw clean compile

# Run tests
./mvnw test

# Package as JAR
./mvnw clean package -DskipTests

# Run the packaged JAR
java -jar target/trading-0.0.1-SNAPSHOT.jar
```

---

## Stopping & Cleanup

```bash
# Stop the backend (Ctrl+C), then:
docker compose down          # stop PostgreSQL (data preserved)
docker compose down -v       # stop + wipe all data (fresh start)
```

---

## Testing

See [API_TESTING.md](./API_TESTING.md) for full `curl`-based walkthrough covering:
- User registration → phone verification → login
- All market-data endpoints with example values
- Instrument lookup and search
- Admin session and instrument management
- Error scenarios (service down, invalid symbol, missing auth)
