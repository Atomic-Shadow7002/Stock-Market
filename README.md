# Stock Market Application Workspace

A complete, high-performance stock market trading platform backend and market-data service built with **Java 21** and **Spring Boot 4.1.0**, featuring PostgreSQL, Flyway migrations, JWT security, Bucket4j rate-limiting, and a dedicated **Angel One SmartAPI Market Data Proxy Gateway**.

---

## 🏛 System Architecture

The project is structured as a decoupled microservices architecture designed for reliability, strict security boundaries, and high-throughput market data fan-out.

```
┌──────────────────────────────────────────────────────────────────┐
│                 Client Applications / Consumers                  │
│       (Web Frontend, Mobile Apps, External Trading Bots)        │
└─────────────────────────────────┬────────────────────────────────┘
                                  │  Authorization: Bearer <JWT>
                                  ▼
┌──────────────────────────────────────────────────────────────────┐        ┌──────────────────────────────┐
│                    Stock-Market-Backend                      │        │       PostgreSQL 17          │
│                       (Port: 8080)                               │        │       (Port: 5432)           │
│  - User Auth (Register, Login, JWT, Refresh Tokens)              │◄──────►│ - users                      │
│  - Phone / Email OTP Verification                                │        │ - refresh_tokens             │
│  - User Self-Service Profile & Password Management               │        │ - otp_verifications          │
│  - Admin User Management (List, Suspend, Promote)                │        └──────────────────────────────┘
│  - Market Data Proxy (/market/**) ──────────────────────────┐    │
│  - Instrument Lookup Proxy (/instruments/**)                │    │
│  - Angel One Admin Proxy (/admin/angelone/**)               │    │
└─────────────────────────────────────────────────────────────┼────┘
                                                              │  X-Internal-Api-Key
                                                              ▼  (Server-to-Server)
                                           ┌────────────────────────────────────┐
                                           │      angelone-market-service       │
                                           │            (Port: 8081)            │
                                           │  - Single Shared AngelOne Session  │
                                           │  - Instrument Master Index (~140k) │
                                           │  - Multi-Cache Layer (Caffeine)    │
                                           │  - Live WebSocket Tick Stream      │
                                           └──────────────────┬─────────────────┘
                                                              │
                                                              ▼  SmartAPI REST & WS
                                           ┌────────────────────────────────────┐
                                           │     Angel One SmartAPI Gateway     │
                                           └────────────────────────────────────┘
```

### Why a Two-Service Architecture?
1. **Single Broker Session Limit**: Angel One SmartAPI allows very few concurrent sessions per user account. The `angelone-market-service` logs in **once** with a single system credential and fans market data out to all application users. End users never log into Angel One directly.
2. **Strict Security Isolation**: The `Stock-Market-Backend` manages all user identity, passwords, and JWTs. The `angelone-market-service` is an internal gateway guarded by a shared `X-Internal-Api-Key` secret. No user credentials or JWT secrets ever enter the market service.

---

## 🛠 Technology Stack

| Layer | Technology | Details |
| :--- | :--- | :--- |
| **Language** | Java 21 | Modern LTS features (Records, Sealed Types, Pattern Matching) |
| **Backend Framework** | Spring Boot 4.1.0 | Spring Framework 7, Spring Security, Spring WebMVC |
| **Database** | PostgreSQL 17 | Relational storage for users, auth, and OTP verifications |
| **Database Migrations**| Flyway | Managed SQL schema versions (`V1` through `V5`) |
| **Security & Auth** | Spring Security + JJWT | Stateless JWT authentication, role-based access control (`USER`, `ADMIN`) |
| **Rate Limiting** | Bucket4j 8.19.0 | In-memory token bucket rate-limiting (per-IP and per-User) |
| **Caching** | Caffeine Cache | Multi-tier TTL cache for Quotes, Candles, Greeks, Margin, and OI |
| **Broker Gateway** | Angel One SmartAPI | SmartAPI REST 2.0 & WebSocket Streaming 2.0 binary tick parser |
| **Containerization** | Docker & Docker Compose | PostgreSQL 17 containerized runtime |
| **Build Tool** | Maven 3.x | Independent build pipelines for each microservice |

---

## 📁 Repository Structure

```
Stock-Market/
├── README.md                           # Master project documentation (this file)
├── angelone-integration-guide.md       # Comprehensive integration guide & architecture deep-dive
│
├── Stock-Market-Backend/               # Main Application Backend (Port 8080)
│   ├── src/main/java/com/luffy/trading/
│   │   ├── angelone/                   # Angel One integration client (RestClient, Config, Exception)
│   │   ├── market/                     # Proxy Controllers (/market/**, /instruments/**, /admin/angelone/**)
│   │   ├── auth/                       # Register, Login, Refresh, Logout, JwtService, JwtFilter
│   │   ├── user/                       # User entity, UserRepository, UserController, UserService
│   │   ├── admin/                      # AdminController, AdminUserService, AdminSeeder
│   │   ├── otp/                        # OtpController, OtpService, LoggingOtpSender, OtpCleanupScheduler
│   │   ├── config/                     # SecurityConfig, RateLimitService, JacksonConfig
│   │   ├── exception/                  # GlobalExceptionHandler, Custom Exceptions
│   │   └── response/                   # ApiResponse<T> standardized JSON envelope
│   ├── src/main/resources/
│   │   ├── application.yml             # App configuration + angelone properties
│   │   └── db/migration/               # Flyway SQL migrations (V1__... to V5__...)
│   ├── .env.example                    # Backend environment variables template
│   ├── API_TESTING.md                  # Detailed curl testing guide & endpoint reference
│   ├── docker-compose.yml              # PostgreSQL 17 compose manifest
│   └── pom.xml                         # Maven dependencies & build rules
│
├── angelone-market-service/            # Standalone Market Data Gateway (Port 8081)
│   ├── src/main/java/com/angelone/angelone_market_service/
│   │   ├── session/                    # AngelSessionManager, TOTP Login, SessionController
│   │   ├── client/                     # AngelRestCaller, SmartAPI Headers builder
│   │   ├── marketdata/                 # MarketDataController, MarketDataService (Quotes, Candles, Greeks, OI)
│   │   ├── instrument/                 # InstrumentService, Daily Scrip Master Index (~140k items)
│   │   ├── feed/                       # AngelFeedClient, TickParser, SubscriptionManager
│   │   ├── broadcast/                  # PublicFeedWebSocketHandler (/ws/feed)
│   │   ├── config/                     # InternalApiKeyFilter, CacheConfig, AppConfig
│   │   └── exception/                  # GlobalExceptionHandler
│   ├── data/                           # Instruments disk cache (instruments-cache.json)
│   ├── .env.example                    # Market service environment template
│   ├── README.md                       # Service-specific documentation
│   ├── API_TESTING.md                  # Market service curl testing collection
│   └── pom.xml                         # Maven build file
│
└── Stock-market-Frontend/              # Web User Interface (Vite + React + TypeScript)
```

---

## ⚡ What's Implemented & Verified

### ✅ Phase 1: Core Foundation & Database
- PostgreSQL 17 setup with Flyway schema migration pipeline:
  - `V1__create_users.sql`: Core user schema with E.164 phone requirement and role definitions.
  - `V2__create_refresh_tokens.sql`: Secure refresh token persistence with cascading deletion.
  - `V3__add_verification_flags_to_users.sql`: `phone_verified` and `email_verified` boolean flags.
  - `V4__create_otp_verifications.sql`: Single-use OTP table with BCrypt hashed codes.
  - `V5__widen_otp_type_for_password_reset.sql`: Enum support for `PASSWORD_RESET` OTPs.
- Standardized API envelope `ApiResponse<T>` (`{ success, message, data, timestamp }`).
- Centralized `GlobalExceptionHandler` with clean error mapping.

### ✅ Phase 2: User Authentication & JWT Security
- User registration via `POST /auth/register` (triggers automatic phone OTP).
- Multi-identifier login via `POST /auth/login` (supports Phone or Email + Password).
- Phone verification guard: logins are blocked (`401 Unauthorized`) until the phone is verified.
- JWT Access Tokens (15-min expiry) & Refresh Tokens (7-day expiry).
- Refresh token rotation (`POST /auth/refresh`) and explicit revocation (`POST /auth/logout`).

### ✅ Phase 3: OTP System & Rate-Limiting
- Secure OTP generation using `SecureRandom` and BCrypt hashing.
- Single-use enforcement, 5-minute expiration, and 5-attempt lockout security.
- Background cleanup scheduler `OtpCleanupScheduler` running every 10 minutes.
- In-memory `RateLimitService` powered by Bucket4j:
  - **Registration**: Max 5 attempts / hour / IP.
  - **Login**: Max 10 attempts / 15 minutes / IP.
  - **OTP Send**: Max 3 attempts / 15 minutes / User, 5 attempts / hour / IP.

### ✅ Phase 4: User Self-Service & Admin Management
- `GET /users/me` & `PUT /users/me`: Profile retrieval and update.
- `POST /users/me/password/otp` & `PUT /users/me/password`: Two-step password change using `PASSWORD_RESET` OTPs (revokes all active refresh tokens on success).
- `AdminSeeder`: Automatic startup bootstrapping of the primary `ADMIN` account.
- Admin management endpoints (`/admin/users/**`): Paginated user search, profile inspection, account suspension (`enabled: false`), and promotion to `ADMIN`. Double-guarded via `@PreAuthorize("hasRole('ADMIN')")` and `SecurityConfig`.

### ✅ Phase 5: Angel One Integration & Market Gateway (COMPLETE)
- **`angelone-market-service` Gateway (Port 8081)**:
  - Automated TOTP authentication (`AngelSessionManager`) with scheduled token refresh.
  - Instrument Master Service: Downloads and indexes ~140,000 Angel One instruments daily.
  - Caffeine caching layer for Quotes, Candles, Greeks, Open Interest, Brokerage, and Margin calculations.
  - Live tick WebSocket feed at `/ws/feed` with reference-counted subscription deduplication.
- **Backend Proxy Layer (`Stock-Market-Backend` Port 8080)**:
  - `AngelOneConfig` & `AngelOneProperties`: Centralized HTTP client configured with base URL and `X-Internal-Api-Key`.
  - `AngelOneClient`: Microservice communication gateway featuring UriBuilder relative path resolution and custom error mapping (`AngelOneServiceException` → 502 Bad Gateway / 503 Service Unavailable).
  - `MarketController`: 8 endpoints proxying real-time quote, historical candles, option Greeks, brokerage calculator, margin calculator, historical open interest, intraday eligible scrips, and cautionary scrips.
  - `InstrumentProxyController`: 4 endpoints proxying symbol resolution, reverse token lookup, search, and instrument index health status.
  - `AngelOneAdminController`: 3 admin-only endpoints proxying session health, force re-login, and force instrument index refresh.

### ✅ Phase 6: Quality Assurance & Verification
- **27/27 Automated Integration Test Suite**: Complete verification executed against live PostgreSQL and active SmartAPI session. All endpoints confirmed operational.

---

## 📊 Complete API Endpoint Reference

### 1. Authentication (`/auth/**` — Public)
| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `POST` | `/auth/register` | Register a new user account (fires phone OTP) |
| `POST` | `/auth/login` | Login with phone/email + password (blocked until phone verified) |
| `POST` | `/auth/refresh` | Exchange a refresh token for a fresh access token |
| `POST` | `/auth/logout` | Revoke a refresh token and end session |

### 2. OTP Verification (`/otp/**` — Requires JWT)
| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `POST` | `/otp/send` | Send OTP to phone or email (`{ "type": "PHONE" \| "EMAIL" }`) |
| `POST` | `/otp/verify` | Verify 6-digit OTP code (`{ "type", "code" }`) |

### 3. User Self-Service (`/users/**` — Requires JWT)
| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `GET` | `/users/me` | Get currently authenticated user profile |
| `PUT` | `/users/me` | Update profile information (`firstName`, `lastName`, `email`) |
| `POST` | `/users/me/password/otp` | Request a `PASSWORD_RESET` OTP to registered phone |
| `PUT` | `/users/me/password` | Change password using OTP (`{ "code", "newPassword" }`) |

### 4. Instrument Lookup (`/instruments/**` — Requires JWT)
| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `GET` | `/instruments/resolve` | Resolve symbol → instrument record (`?exchange=NSE&symbol=SBIN-EQ`) |
| `GET` | `/instruments/token` | Reverse lookup: token → instrument (`?exchange=NSE&token=3045`) |
| `GET` | `/instruments/search` | Search instruments by prefix/name (`?query=RELIANCE&limit=10`) |
| `GET` | `/instruments/status` | Instrument index health (total count, last updated, stale flag) |

### 5. Market Data Proxy (`/market/**` — Requires JWT)
| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `GET` | `/market/quote` | Real-time quotes (`?mode=LTP\|OHLC\|FULL&exchange=NSE&symbols=SBIN-EQ`) |
| `GET` | `/market/candles` | Historical OHLCV candles (`?exchange=NSE&symbol=SBIN-EQ&interval=ONE_DAY&fromDate=...&toDate=...`) |
| `GET` | `/market/greeks` | Option Greeks (`?name=NIFTY&expiryDate=28AUG2025`) |
| `POST` | `/market/brokerage` | Estimate brokerage and transaction taxes for an order basket |
| `POST` | `/market/margin` | Calculate real-time margin required for position basket |
| `GET` | `/market/oi` | Historical open interest for F&O instruments (`?exchange=NFO&symbolToken=42612&...`) |
| `GET` | `/market/intraday-eligible` | List of scrips eligible for intraday trading + margin multipliers |
| `GET` | `/market/cautionary` | List of ASM/GSM caution-flagged scrips |

### 6. Admin — User Management (`/admin/users/**` — Requires `ROLE_ADMIN`)
| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `GET` | `/admin/users` | List/search all registered users (paginated, optional `?phone=` filter) |
| `GET` | `/admin/users/{id}` | Inspect full profile of a specific user |
| `PATCH` | `/admin/users/{id}/status` | Enable or disable (suspend) a user account (cannot target self) |
| `POST` | `/admin/users/{id}/promote` | Promote a user to `ADMIN` role |

### 7. Admin — Angel One Service Management (`/admin/angelone/**` — Requires `ROLE_ADMIN`)
| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `GET` | `/admin/angelone/session/status` | Check if the Angel One gateway has an active broker session |
| `POST` | `/admin/angelone/session/relogin` | Force an immediate re-login to Angel One SmartAPI (break-glass) |
| `POST` | `/admin/angelone/instruments/refresh` | Force an immediate refresh of the 140k+ instrument master index |

---

## ⚙️ Environment Configuration

Both backend services require configuration via `.env` files.

### 1. Backend Environment (`Stock-Market-Backend/.env`)
```env
JWT_SECRET=KyQzQOYN+N1t/b913e3QKEr5jIu2nX1ySPHT0iXE7MoJyNYkrK629tzLtpx95zyDiiebRA1h5id7bYicX7A9mQ==

DB_URL=jdbc:postgresql://localhost:5432/trading
DB_USERNAME=postgres
DB_PASSWORD=postgres

ADMIN_SEED_PHONE=+916003086907
ADMIN_SEED_PASSWORD=Admin1234
ADMIN_SEED_FIRST_NAME=Admin
ADMIN_SEED_LAST_NAME=User
ADMIN_SEED_EMAIL=admin1234@gmail.com

# Shared secret — MUST BE IDENTICAL to angelone-market-service/.env
INTERNAL_API_KEY=5272f5f8f04449ce72a4ba87a65d5901a35c0ef23c8f966cc3a5b08fc6f8b40d
ANGEL_ONE_BASE_URL=http://localhost:8081
ANGEL_ONE_WS_URL=ws://localhost:8081/ws/feed
```

### 2. Angel One Service Environment (`angelone-market-service/.env`)
```env
ANGEL_CLIENT_CODE=your_client_code
ANGEL_PIN=your_4_digit_pin
ANGEL_TOTP_SECRET=your_32_character_base32_totp_secret
ANGEL_API_KEY=your_smartapi_key
ANGEL_LOCAL_IP=127.0.0.1
ANGEL_PUBLIC_IP=127.0.0.1
ANGEL_MAC_ADDRESS=AA:BB:CC:DD:EE:FF

# Shared secret — MUST BE IDENTICAL to Stock-Market-Backend/.env
INTERNAL_API_KEY=5272f5f8f04449ce72a4ba87a65d5901a35c0ef23c8f966cc3a5b08fc6f8b40d
FEED_ALLOWED_ORIGINS=http://localhost:8080,http://localhost:3000
```

---

## 🚀 How to Run & Verify

### Step 1: Start PostgreSQL
```bash
cd Stock-Market-Backend
docker compose up -d
```

### Step 2: Start the Angel One Market Gateway
```bash
cd ../angelone-market-service
./mvnw spring-boot:run
```
*Wait for log output:* `AngelOne login succeeded` & `Instrument index refreshed`.

### Step 3: Start the Stock Market Backend
```bash
cd ../Stock-Market-Backend
./mvnw spring-boot:run
```
*Wait for log output:* `Started TradingApplication`.

### Step 4: Run the Complete 27-Endpoint Automated Test Suite
```bash
chmod +x /tmp/test_all_endpoints.sh
/tmp/test_all_endpoints.sh
```

---

## 📄 Related Documentation
- [Angel One Integration Guide](file:///home/atomic-shadow/development/Stock-Market/angelone-integration-guide.md): Comprehensive deep-dive on microservice communication, session lifecycle, and security boundaries.
- [Backend Testing Guide](file:///home/atomic-shadow/development/Stock-Market/Stock-Market-Backend/API_TESTING.md): Step-by-step curl walkthrough for testing every backend endpoint manually.
- [Backend Implementation Walkthrough](file:///home/atomic-shadow/.gemini/antigravity-ide/brain/0702cd8b-85e0-4376-978d-cf9926ff3f36/walkthrough.md): Verification report detailing bug fixes, architectural choices, and test outputs.
