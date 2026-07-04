# Stock Market Backend

> Java 21 · Spring Boot 3 · PostgreSQL 17 · Flyway · Docker · JWT · Bucket4j

---

## Tech Stack

| Layer            | Technology              |
| ---------------- | ----------------------- |
| Language         | Java 21                 |
| Framework        | Spring Boot 3           |
| Security         | Spring Security + JWT   |
| Database         | PostgreSQL 17           |
| Migrations       | Flyway                  |
| Rate Limiting    | Bucket4j (in-memory)    |
| Containerization | Docker & Docker Compose |
| Build Tool       | Maven                   |

---

## Auth Strategy

| Method                 | Status    | Identifier       |
| ---------------------- | --------- | ---------------- |
| Phone + Password       | ✅ MVP    | Phone (required) |
| Email + Password       | ✅ MVP    | Email (optional) |
| Phone OTP verification | ✅ MVP    | Phone (required) |
| Email OTP verification | ✅ MVP    | Email (optional) |
| Phone + OTP **login**  | 🔜 Future | —                |

> **Phone is always required.** Email is optional and can be added later by the user.
> Login can be done via phone or email, but registration always requires phone.
> **New:** registration now also kicks off phone verification via OTP, and login is
> blocked until the phone is verified. OTP **login** (replacing password with a code)
> is still future work — what's shipped now is OTP **verification** of an
> already-registered phone/email.

---

## Folder Structure

```
trading/
│
├── docker-compose.yml
├── pom.xml
├── README.md
├── .gitignore
│
└── src/
    ├── main/
    │   ├── java/com/luffy/trading/
    │   │   │
    │   │   ├── TradingApplication.java
    │   │   │
    │   │   ├── config/
    │   │   │   ├── SecurityConfig.java
    │   │   │   ├── CorsConfig.java
    │   │   │   ├── JacksonConfig.java
    │   │   │   ├── RateLimitService.java
    │   │   │   ├── AdminSeedProperties.java
    │   │   │   └── AdminSeeder.java
    │   │   │
    │   │   ├── exception/
    │   │   │   ├── JwtValidationException.java
    │   │   │   ├── GlobalExceptionHandler.java
    │   │   │   ├── ResourceNotFoundException.java
    │   │   │   ├── DuplicateResourceException.java
    │   │   │   ├── InvalidOtpException.java
    │   │   │   ├── OtpExpiredException.java
    │   │   │   ├── TooManyOtpAttemptsException.java
    │   │   │   ├── RateLimitExceededException.java
    │   │   │   └── IllegalSelfActionException.java
    │   │   │
    │   │   ├── response/
    │   │   │   └── ApiResponse.java
    │   │   │
    │   │   ├── util/
    │   │   │   └── SecurityUtils.java
    │   │   │
    │   │   ├── auth/
    │   │   │   ├── AuthController.java
    │   │   │   ├── AuthService.java
    │   │   │   ├── AuthRepository.java
    │   │   │   ├── JwtService.java
    │   │   │   ├── JwtFilter.java
    │   │   │   ├── JwtProperties.java
    │   │   │   ├── RefreshToken.java
    │   │   │   ├── LoginRequest.java
    │   │   │   ├── RefreshRequest.java
    │   │   │   ├── RegisterRequest.java
    │   │   │   └── AuthResponse.java
    │   │   │
    │   │   ├── otp/
    │   │   │   ├── OtpType.java
    │   │   │   ├── OtpVerification.java
    │   │   │   ├── OtpRepository.java
    │   │   │   ├── OtpSender.java
    │   │   │   ├── LoggingOtpSender.java
    │   │   │   ├── OtpService.java
    │   │   │   ├── SendOtpRequest.java
    │   │   │   ├── VerifyOtpRequest.java
    │   │   │   ├── OtpController.java
    │   │   │   └── OtpCleanupScheduler.java
    │   │   │
    │   │   ├── user/
    │   │   │   ├── User.java
    │   │   │   ├── Role.java
    │   │   │   ├── UserRepository.java
    │   │   │   ├── UserController.java
    │   │   │   ├── UserService.java
    │   │   │   ├── UserResponse.java
    │   │   │   ├── UpdateProfileRequest.java
    │   │   │   └── ChangePasswordRequest.java
    │   │   │
    │   │   ├── admin/
    │   │   │   ├── AdminController.java
    │   │   │   ├── AdminUserService.java
    │   │   │   └── UpdateUserStatusRequest.java
    │   │   │
    │   │   ├── market/                                Phase 4 — not built yet
    │   │   │   ├── MarketController.java
    │   │   │   ├── MarketService.java
    │   │   │   ├── SmartApiClient.java
    │   │   │   ├── SmartApiProperties.java
    │   │   │   ├── QuoteResponse.java
    │   │   │   └── SearchResponse.java
    │   │   │
    │   │   └── watchlist/                              Phase 5 — not built yet
    │   │       ├── Watchlist.java
    │   │       ├── WatchlistItem.java
    │   │       ├── WatchlistRepository.java
    │   │       ├── WatchlistItemRepository.java
    │   │       ├── WatchlistController.java
    │   │       ├── WatchlistService.java
    │   │       ├── WatchlistRequest.java
    │   │       └── WatchlistResponse.java
    │   │
    │   └── resources/
    │       ├── application.yml
    │       └── db/
    │           └── migration/
    │               ├── V1__create_users.sql
    │               ├── V2__create_refresh_tokens.sql
    │               ├── V3__add_verification_flags_to_users.sql
    │               ├── V4__create_otp_verifications.sql
    │               ├── V5__widen_otp_type_for_password_reset.sql
    │               └── V6__create_watchlists.sql not built yet
    │
    └── test/
        └── java/com/luffy/trading/
            └── TradingApplicationTests.java
```

---

## application.yml

```yaml
spring:
  application:
    name: trading

  datasource:
    url: jdbc:postgresql://localhost:5432/trading
    username: postgres
    password: postgres
    driver-class-name: org.postgresql.Driver

  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: true
    properties:
      hibernate:
        format_sql: true

  flyway:
    enabled: true
    locations: classpath:db/migration

server:
  port: 8080

jwt:
  secret: ${JWT_SECRET:local-dev-secret-change-in-prod}
  access-token-expiry: 900
  refresh-token-expiry: 604800

# NEW
otp:
  sender:
    logging # dev default — prints OTP to logs. Switch to
    # "twilio" (or another provider id) once a real
    # OtpSender implementation exists. NEVER use
    # "logging" in production.
  cleanup-interval-ms: 600000 # how often OtpCleanupScheduler purges expired rows

logging:
  level:
    com.luffy.trading: DEBUG
    org.springframework.security: DEBUG
```

---

## docker-compose.yml

```yaml
version: "3.8"

services:
  postgres:
    image: postgres:17
    environment:
      POSTGRES_DB: trading
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data

volumes:
  postgres_data:
```

---

## pom.xml — added dependency

```xml
<!-- Rate limiting (Java 17+ build) -->
<dependency>
    <groupId>com.bucket4j</groupId>
    <artifactId>bucket4j_jdk17-core</artifactId>
    <version>8.19.0</version>
</dependency>
```

---

## Database Migrations

```sql
-- V1__create_users.sql
CREATE TABLE users (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    first_name  VARCHAR(100) NOT NULL,
    last_name   VARCHAR(100) NOT NULL,
    phone       VARCHAR(20)  NOT NULL UNIQUE,              -- required, E.164 e.g. +919876543210
    email       VARCHAR(255) UNIQUE,                       -- optional
    password    VARCHAR(255) NOT NULL,
    role        VARCHAR(20)  NOT NULL DEFAULT 'USER',
    enabled     BOOLEAN      NOT NULL DEFAULT true,         -- account suspension support
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- V2__create_refresh_tokens.sql
CREATE TABLE refresh_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token       TEXT         NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- V3__add_verification_flags_to_users.sql   ← NEW
ALTER TABLE users
    ADD COLUMN phone_verified BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT false;

-- V4__create_otp_verifications.sql          ← NEW
CREATE TABLE otp_verifications (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type        VARCHAR(10)  NOT NULL CHECK (type IN ('PHONE', 'EMAIL')),
    code_hash   VARCHAR(255) NOT NULL,           -- BCrypt hash only, plaintext never stored
    expires_at  TIMESTAMPTZ  NOT NULL,
    attempts    INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_otp_user_type UNIQUE (user_id, type)  -- one active OTP per user per type
);

CREATE INDEX idx_otp_expires_at ON otp_verifications (expires_at);

-- V5__widen_otp_type_for_password_reset.sql          ← NEW (Phase 3)
-- Widens type from VARCHAR(10) to VARCHAR(20) and updates the CHECK
-- constraint so PASSWORD_RESET (14 chars) fits alongside PHONE / EMAIL.
ALTER TABLE otp_verifications
    ALTER COLUMN type TYPE VARCHAR(20);

ALTER TABLE otp_verifications
    DROP CONSTRAINT IF EXISTS otp_verifications_type_check;

ALTER TABLE otp_verifications
    ADD CONSTRAINT otp_verifications_type_check
    CHECK (type IN ('PHONE', 'EMAIL', 'PASSWORD_RESET'));
```

---

## API Endpoints

### Auth

| Method | Endpoint         | Auth | Rate Limit       | Description                                                |
| ------ | ---------------- | ---- | ---------------- | ---------------------------------------------------------- |
| POST   | `/auth/register` | No   | 5 / hour / IP    | Create account (phone required), fires phone OTP           |
| POST   | `/auth/login`    | No   | 10 / 15 min / IP | Login via phone or email — **blocked if phone unverified** |
| POST   | `/auth/refresh`  | No   | —                | Refresh access token                                       |
| POST   | `/auth/logout`   | Yes  | —                | Revoke refresh token                                       |

### OTP — NEW

| Method | Endpoint      | Auth | Rate Limit                          | Description                                                               |
| ------ | ------------- | ---- | ----------------------------------- | ------------------------------------------------------------------------- |
| POST   | `/otp/send`   | Yes  | 3 / 15 min / user, 5 / hour / IP    | Generate + dispatch a new OTP, invalidating any previous one of that type |
| POST   | `/otp/verify` | Yes  | 5 wrong attempts invalidate the OTP | Verify a code, flips `phoneVerified`/`emailVerified` to `true`            |

### User (self-service) — Phase 3 ✅ NEW

| Method | Endpoint                 | Auth | Description                                                                   |
| ------ | ------------------------ | ---- | ----------------------------------------------------------------------------- |
| GET    | `/users/me`              | Yes  | Get own profile                                                               |
| PUT    | `/users/me`              | Yes  | Update `firstName`/`lastName`/`email` — does **not** reset `emailVerified`    |
| POST   | `/users/me/password/otp` | Yes  | Step 1 of changing your password — sends a `PASSWORD_RESET` OTP to your phone |
| PUT    | `/users/me/password`     | Yes  | Step 2 — body `{code, newPassword}`; on success revokes all refresh tokens    |

### Admin — Phase 3 ✅ NEW

All routes below require `ROLE_ADMIN`, enforced twice: `@PreAuthorize` on
`AdminController` and a `/admin/**` matcher in `SecurityConfig`.

| Method | Endpoint                          | Auth  | Description                                                     |
| ------ | --------------------------------- | ----- | --------------------------------------------------------------- |
| GET    | `/admin/users?phone=&page=&size=` | Admin | List/search all users, optional partial phone filter, paginated |
| GET    | `/admin/users/{id}`               | Admin | View any single user's full profile                             |
| PATCH  | `/admin/users/{id}/status`        | Admin | body `{enabled}` — enable/disable; blocked from targeting self  |
| POST   | `/admin/users/{id}/promote`       | Admin | Promotes a user to `ADMIN`, idempotent                          |

### Market

| Method | Endpoint                    | Auth | Description     |
| ------ | --------------------------- | ---- | --------------- |
| GET    | `/market/quote?symbol=INFY` | Yes  | Get stock quote |
| GET    | `/market/search?q=Infosys`  | Yes  | Search stocks   |

### Watchlist

| Method | Endpoint                          | Auth | Description            |
| ------ | --------------------------------- | ---- | ---------------------- |
| GET    | `/watchlists`                     | Yes  | List all watchlists    |
| POST   | `/watchlists`                     | Yes  | Create watchlist       |
| DELETE | `/watchlists/{id}`                | Yes  | Delete watchlist       |
| POST   | `/watchlists/{id}/items`          | Yes  | Add stock to watchlist |
| DELETE | `/watchlists/{id}/items/{symbol}` | Yes  | Remove from watchlist  |

---

## JWT Flow

```
POST /auth/register
        │
        ▼
  rate limit: 5/hour/IP — 429 if exceeded
        │
        ▼
  validate: phone present + E.164 format
  check: phone not already registered
  check: email not already registered (if provided)
        │
        ▼
  BCrypt(password) → save User (phoneVerified=false, emailVerified=false)
        │
        ▼
  OtpService.generateAndSend(user, PHONE)        ← NEW
        │
        ▼
  generate accessToken (sub = userId, 15 min)
  save refreshToken → DB (7 days)
        │
        ▼
  return { accessToken, refreshToken }


POST /auth/login
        │
        ▼
  rate limit: 10/15min/IP — 429 if exceeded
        │
        ▼
  body: { phone? | email?, password* }
        │
        ▼
  if phone provided  → findByPhone
  else if email      → findByEmail
  else               → 400 Bad Request
        │
        ▼
  BCrypt.matches(raw, hashed) → 401 if fails
        │
        ▼
  user.phoneVerified == false?  → 401 "not verified"     ← NEW
        │
        ▼
  generate accessToken (sub = userId)
  save refreshToken → DB
        │
        ▼
  return { accessToken, refreshToken }


Every protected request
  Authorization: Bearer <accessToken>
        │
        ▼
  JwtFilter → extract userId from sub claim
        │
        ▼
  userRepository.findById(userId) → 401 if not found
        │
        ▼
  set SecurityContext → controller runs


POST /auth/refresh
  body: { refreshToken }
        │
        ▼
  look up token in DB → 401 if missing or expired
        │
        ▼
  return new accessToken (refreshToken unchanged)


POST /auth/logout
  Authorization: Bearer <accessToken>
        │
        ▼
  delete refreshToken from DB → session invalidated
```

### OTP Flow

```
POST /otp/send
  Authorization: Bearer <accessToken>
  body: { type: PHONE | EMAIL }
        │
        ▼
  rate limit: 5/hour/IP → 429 if exceeded
        │
        ▼
  resolve current user from token
        │
        ▼
  OtpService.generateAndSend(user, type)
    │
    ├─ rate limit: 3/15min/user → 429 if exceeded
    ├─ delete any existing OTP of this type for this user
    ├─ generate 6-digit code via SecureRandom
    ├─ BCrypt-hash it → store hash + expiresAt(+5min) + attempts=0
    └─ OtpSender.send(user, type, plainCode)   ← LoggingOtpSender logs it (dev)
        │
        ▼
  return 200 OK


POST /otp/verify
  Authorization: Bearer <accessToken>
  body: { type: PHONE | EMAIL, code: "123456" }
        │
        ▼
  resolve current user from token
        │
        ▼
  OtpService.verify(user, type, code)
    │
    ├─ no active OTP for (user, type)?        → 404
    ├─ expired?  delete row                    → 400 OtpExpiredException
    ├─ attempts >= 5?  delete row               → 429 TooManyOtpAttemptsException
    ├─ BCrypt.matches(code, hash)?
    │     no  → attempts++; if now >=5, delete + 429; else → 400 InvalidOtpException
    │     yes → delete row (single-use)
    │           set user.phoneVerified / emailVerified = true
        │
        ▼
  return 200 OK


Background — OtpCleanupScheduler
  every 10 min (configurable via otp.cleanup-interval-ms)
        │
        ▼
  DELETE FROM otp_verifications WHERE expires_at < now()
```

### Password Change Flow

```

POST /users/me/password/otp
Authorization: Bearer <accessToken>
│
▼
OtpService.generateAndSend(currentUser, PASSWORD_RESET)
(same rate limit + single-active-OTP-per-type machinery as PHONE/EMAIL —
this is a distinct OtpType, so it doesn't collide with an in-flight
phone-verification OTP)
│
▼
return 200 OK — code logged by LoggingOtpSender in dev

PUT /users/me/password
Authorization: Bearer <accessToken>
body: { code, newPassword }
│
▼
OtpService.verify(currentUser, PASSWORD_RESET, code)
(same expiry/attempt-lockout rules as PHONE/EMAIL, but does NOT flip
phoneVerified/emailVerified on success — that's not what this OTP proves)
│
▼
BCrypt(newPassword) → save user
│
▼
authRepository.deleteAllByUserId(user.id) ← revoke every refresh token;
forces re-login everywhere
│
▼
return 200 OK

```

### Admin Flow

```

GET /admin/users?phone=&page=&size=
Authorization: Bearer <accessToken>
│
▼
@PreAuthorize("hasRole('ADMIN')") + SecurityConfig /admin/\*\* rule → 403 if not admin
│
▼
phone param present? → findByPhoneContainingIgnoreCase (partial match)
else → findAll (paginated)

PATCH /admin/users/{id}/status { enabled }
│
▼
id == caller's own id? → 400 IllegalSelfActionException
│
▼
set user.enabled, save
│
▼
enabled == false? → authRepository.deleteAllByUserId(user.id)
(JwtFilter already blocks a disabled user's access
token; this also kills their refresh token so
/auth/refresh can't mint a new one)

POST /admin/users/{id}/promote
│
▼
set user.role = ADMIN, save (idempotent — promoting an existing admin is a no-op)

```

### Admin Bootstrap

```

Application startup
│
▼
AdminSeeder.run() (ApplicationRunner, runs once per boot)
│
▼
userRepository.existsByRole(ADMIN)?
yes → do nothing (already bootstrapped)
no → admin.seed.phone/password configured?
no → log a warning, skip
yes → create User(role=ADMIN, phoneVerified=true) using the
real PasswordEncoder bean, save

```

## Rate Limiting — NEW

In-memory Bucket4j buckets, keyed by IP or by user id:

| Limit                 | Scope    | Window                                                                                     |
| --------------------- | -------- | ------------------------------------------------------------------------------------------ |
| 5 registrations       | per IP   | 1 hour                                                                                     |
| 10 login attempts     | per IP   | 15 minutes                                                                                 |
| 3 OTP sends           | per user | 15 minutes                                                                                 |
| 5 OTP sends           | per IP   | 1 hour                                                                                     |
| 5 OTP verify attempts | per OTP  | until invalidated (not time-windowed — tracked on the `otp_verifications.attempts` column) |

> Buckets live in a `ConcurrentHashMap` inside `RateLimitService`, so they're
> per-JVM-instance — fine for a single instance. Scaling horizontally later
> means swapping the map for `bucket4j-redis` / `-hazelcast` / `-jcache`; the
> limit definitions themselves don't change.

---

## Build Order

### Phase 1 — Foundation ✅

- [x] Folder structure
- [x] `application.yml`
- [x] `docker-compose.yml`
- [x] `ApiResponse<T>`
- [x] `GlobalExceptionHandler`
- [x] Flyway + `V1__create_users.sql` (firstName, lastName, phone required, email optional, enabled)

### Phase 2 — Auth ✅

- [x] `Role` enum
- [x] `User` entity (firstName, lastName, phone NOT NULL, email nullable, enabled), `UserRepository`
- [x] `PasswordEncoder` bean (BCrypt)
- [x] `JwtService` — generate + validate tokens (sub = userId)
- [x] `JwtFilter` — validate Bearer token on every request
- [x] `SecurityConfig` — permit `/auth/**`, lock everything else
- [x] `RegisterRequest` — firstName + lastName + phone + password required, email optional
- [x] `LoginRequest` — phone or email + password
- [x] `AuthController` — register, login, refresh, logout
- [x] `RefreshToken` entity + `V2__create_refresh_tokens.sql`

### Phase 2.5 — OTP Verification + Rate Limiting ✅ NEW

- [x] `V3__add_verification_flags_to_users.sql` — `phone_verified`, `email_verified`
- [x] `V4__create_otp_verifications.sql` — OTP table, one active row per (user, type)
- [x] `OtpType`, `OtpVerification` entity, `OtpRepository`
- [x] `OtpSender` interface + `LoggingOtpSender` (dev) implementation
- [x] `OtpService` — generate/send (SecureRandom + BCrypt hash), verify (single-use, max 5 attempts), invalidate-previous-on-resend
- [x] `OtpController` — `POST /otp/send`, `POST /otp/verify`
- [x] `OtpCleanupScheduler` — periodic purge of expired rows (`@EnableScheduling` added to `TradingApplication`)
- [x] `RateLimitService` (Bucket4j) — registration, login, OTP-send (per-user and per-IP) limits
- [x] `AuthService.register()` — creates user unverified, auto-fires phone OTP
- [x] `AuthService.login()` — blocks unverified phones
- [x] `SecurityUtils` — resolves current user id from JWT auth context
- [x] New exceptions: `InvalidOtpException`, `OtpExpiredException`, `TooManyOtpAttemptsException`, `RateLimitExceededException`

### Phase 3 — User Profile + Admin ✅ NEW

- [x] `V5__widen_otp_type_for_password_reset.sql`
- [x] `OtpType.PASSWORD_RESET`; `OtpService.verify()` no longer flips a
      verification flag for that type
- [x] `UserResponse`, `UpdateProfileRequest`, `ChangePasswordRequest`
- [x] `UserService` / `UserController` — `GET /users/me`, `PUT /users/me`,
      `POST /users/me/password/otp`, `PUT /users/me/password`
- [x] `UserRepository` — `existsByRole`, `findByPhoneContainingIgnoreCase`
- [x] `admin` package — `AdminController`, `AdminUserService`,
      `UpdateUserStatusRequest`
- [x] `AdminSeedProperties` / `AdminSeeder` — bootstraps the first admin on
      startup from config, no hand-typed password hashes
- [x] `SecurityConfig` — `@EnableMethodSecurity`, `/admin/**` → `hasRole("ADMIN")`
- [x] `GlobalExceptionHandler` — `AccessDeniedException` (403),
      `IllegalSelfActionException` (400)

### Phase 4 — Market Data

- [ ] `SmartApiClient` — HTTP calls to Angel SmartAPI
- [ ] `SmartApiProperties`
- [ ] `MarketController` — quote, search

### Phase 5 — Watchlists

- [ ] `V5__create_watchlists.sql`
- [ ] `Watchlist` + `WatchlistItem` entities
- [ ] `WatchlistController` — CRUD + items

### Later (not now)

- Phone + OTP **login** (replacing password with a code) — schema already supports it
- Real `OtpSender` implementation (Twilio / AWS SNS / SES) — interface is ready, see [OTP Delivery](#otp-delivery--migration-path-to-a-real-provider)
- Distributed rate limiting (`bucket4j-redis`) for multi-instance deployments
- Admin endpoint to toggle `enabled` (account suspension)
- Swagger / OpenAPI
- WebSocket
- Redis
- Portfolio simulator
- `application-prod.yml` + deployment

~~Email/phone verification flags (`emailVerified`, `phoneVerified`)~~ — ✅ done, see Phase 2.5

---

## Dev Commands

```bash
# Start PostgreSQL
docker compose up -d

# Run app
./mvnw spring-boot:run

# Stop
Ctrl+C && docker compose down

# Full reset
docker compose down -v && docker compose up -d

# Build JAR
./mvnw clean package

# Run JAR
java -jar target/trading-0.0.1-SNAPSHOT.jar
```

### Testing the OTP flow locally

```bash
# 1. Register — watch the app logs for the OTP (LoggingOtpSender prints it)
curl -X POST localhost:8080/auth/register -H 'Content-Type: application/json' \
  -d '{"firstName":"Abhi","lastName":"Sharma","phone":"+919876543210","password":"secret123"}'

# 2. Verify using the code from the logs (token from step 1's response)
curl -X POST localhost:8080/otp/verify -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer <accessToken>' \
  -d '{"type":"PHONE","code":"123456"}'

# 3. Now login succeeds
curl -X POST localhost:8080/auth/login -H 'Content-Type: application/json' \
  -d '{"phone":"+919876543210","password":"secret123"}'
```
