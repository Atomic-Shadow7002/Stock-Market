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
    │   │   ├── TradingApplication.java          ← @EnableScheduling added (OTP cleanup)
    │   │   │
    │   │   ├── config/
    │   │   │   ├── SecurityConfig.java
    │   │   │   ├── CorsConfig.java
    │   │   │   ├── JacksonConfig.java
    │   │   │   └── RateLimitService.java         ← NEW: Bucket4j buckets
    │   │   │
    │   │   ├── exception/
    │   │   │   ├── JwtValidationException.java
    │   │   │   ├── GlobalExceptionHandler.java     ← updated: OTP/rate-limit handlers
    │   │   │   ├── ResourceNotFoundException.java
    │   │   │   ├── DuplicateResourceException.java
    │   │   │   ├── InvalidOtpException.java        ← NEW
    │   │   │   ├── OtpExpiredException.java         ← NEW
    │   │   │   ├── TooManyOtpAttemptsException.java ← NEW
    │   │   │   └── RateLimitExceededException.java  ← NEW
    │   │   │
    │   │   ├── response/
    │   │   │   └── ApiResponse.java
    │   │   │
    │   │   ├── util/
    │   │   │   └── SecurityUtils.java              ← NEW: current userId from JWT context
    │   │   │
    │   │   ├── auth/
    │   │   │   ├── AuthController.java              ← updated: IP rate limiting
    │   │   │   ├── AuthService.java                 ← updated: triggers OTP, blocks unverified login
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
    │   │   ├── otp/                                ← NEW PACKAGE
    │   │   │   ├── OtpType.java                     enum PHONE / EMAIL
    │   │   │   ├── OtpVerification.java              entity — BCrypt hash only, never plaintext
    │   │   │   ├── OtpRepository.java
    │   │   │   ├── OtpSender.java                    abstraction — Twilio/SES plug in later
    │   │   │   ├── LoggingOtpSender.java             dev-only impl, logs the code
    │   │   │   ├── OtpService.java                  generate / send / verify business logic
    │   │   │   ├── SendOtpRequest.java
    │   │   │   ├── VerifyOtpRequest.java
    │   │   │   ├── OtpController.java                POST /otp/send, POST /otp/verify
    │   │   │   └── OtpCleanupScheduler.java          purges expired OTPs every 10 min
    │   │   │
    │   │   ├── user/
    │   │   │   ├── User.java                         ← updated: phoneVerified, emailVerified
    │   │   │   ├── Role.java
    │   │   │   ├── UserRepository.java
    │   │   │   ├── UserController.java
    │   │   │   ├── UserService.java
    │   │   │   ├── UserResponse.java
    │   │   │   └── UpdateProfileRequest.java
    │   │   │
    │   │   ├── market/
    │   │   │   ├── MarketController.java
    │   │   │   ├── MarketService.java
    │   │   │   ├── SmartApiClient.java
    │   │   │   ├── SmartApiProperties.java
    │   │   │   ├── QuoteResponse.java
    │   │   │   └── SearchResponse.java
    │   │   │
    │   │   └── watchlist/
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
    │       ├── application.yml                       ← updated: otp.* properties
    │       └── db/
    │           └── migration/
    │               ├── V1__create_users.sql
    │               ├── V2__create_refresh_tokens.sql
    │               ├── V3__add_verification_flags_to_users.sql  ← NEW
    │               ├── V4__create_otp_verifications.sql          ← NEW
    │               └── V5__create_watchlists.sql                 ← not built yet (Phase 5 below), reserved next number
    │
    └── test/
        └── java/com/luffy/trading/
            ├── auth/
            │   └── AuthServiceTest.java
            ├── otp/                                  ← NEW
            │   └── OtpServiceTest.java
            ├── user/
            │   └── UserServiceTest.java
            └── watchlist/
                └── WatchlistServiceTest.java
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

-- V5__create_watchlists.sql                  ← NOT YET BUILT (Phase 5) — numbered here as a
-- placeholder so whoever implements watchlists knows which version is next in line.
-- Until it exists, V4 is genuinely the latest applied migration.
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

#### `POST /auth/register` — Request Body

```json
{
  "firstName": "Abhi",
  "lastName": "Sharma",
  "phone": "+919876543210",
  "password": "secret123",
  "email": "abhi@example.com"
}
```

> `firstName`, `lastName`, `phone`, `password` are required. `email` is optional.
> **New:** the created user starts with `phoneVerified=false`, `emailVerified=false`,
> and a phone OTP is generated and dispatched automatically. The response still
> returns a token pair, so the client can call `/otp/verify` immediately.

#### `POST /auth/login` — Request Body

```json
{ "phone": "+919876543210", "password": "secret123" }
```

```json
{ "email": "abhi@example.com", "password": "secret123" }
```

> Either `phone` or `email` must be provided. `phone` takes priority if both are sent.
> **New:** returns `401` with `"Phone number not verified — verify via /otp/verify before logging in"`
> if `phoneVerified` is still `false`.

### OTP — NEW

| Method | Endpoint      | Auth | Rate Limit                          | Description                                                               |
| ------ | ------------- | ---- | ----------------------------------- | ------------------------------------------------------------------------- |
| POST   | `/otp/send`   | Yes  | 3 / 15 min / user, 5 / hour / IP    | Generate + dispatch a new OTP, invalidating any previous one of that type |
| POST   | `/otp/verify` | Yes  | 5 wrong attempts invalidate the OTP | Verify a code, flips `phoneVerified`/`emailVerified` to `true`            |

#### `POST /otp/send` — Request Body

```json
{ "type": "PHONE" }
```

```json
{ "type": "EMAIL" }
```

#### `POST /otp/verify` — Request Body

```json
{ "type": "PHONE", "code": "123456" }
```

> Both endpoints require `Authorization: Bearer <accessToken>` — the same token
> issued at registration, since the account is real before it's verified.
> Codes are 6 digits, expire after 5 minutes, and are single-use (deleted on
> successful verification). In dev mode the code is printed to the application
> logs by `LoggingOtpSender` — see [OTP Delivery](#otp-delivery--migration-path-to-a-real-provider) below.

### User

| Method | Endpoint             | Auth | Description            |
| ------ | -------------------- | ---- | ---------------------- |
| GET    | `/users/me`          | Yes  | Get own profile        |
| PUT    | `/users/me`          | Yes  | Update name(s) / email |
| PUT    | `/users/me/password` | Yes  | Change password        |

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

### OTP Flow — NEW

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

---

## Request / Response Contracts

### RegisterRequest.java

```java
public record RegisterRequest(
    @NotBlank(message = "First name is required")
    String firstName,       // required

    @NotBlank(message = "Last name is required")
    String lastName,        // required

    @NotBlank(message = "Phone is required")
    @Pattern(regexp = "^\\+[1-9]\\d{7,14}$", message = "Phone must be E.164 format")
    String phone,           // required

    @NotBlank(message = "Password is required")
    String password,        // required

    @Email(message = "Email must be valid")
    String email            // optional
) {}
```

### LoginRequest.java

```java
public record LoginRequest(
    String phone,           // optional — phone OR email required
    String email,           // optional
    @NotBlank String password
) {}
// Service validates: at least one of phone/email present
// Priority: phone > email if both sent
// NEW: also rejects login if user.phoneVerified == false
```

### AuthResponse.java

```java
public record AuthResponse(
    String accessToken,
    String refreshToken
) {}
```

### UserResponse.java

```java
public record UserResponse(
    UUID id,
    String firstName,
    String lastName,
    String phone,
    String email,           // nullable
    String role,
    boolean enabled,
    boolean phoneVerified,  // NEW
    boolean emailVerified,  // NEW
    OffsetDateTime createdAt
) {}
```

### OtpType.java — NEW

```java
public enum OtpType {
    PHONE,
    EMAIL
}
```

### SendOtpRequest.java — NEW

```java
public record SendOtpRequest(
    @NotNull(message = "type is required (PHONE or EMAIL)")
    OtpType type
) {}
```

### VerifyOtpRequest.java — NEW

```java
public record VerifyOtpRequest(
    @NotNull(message = "type is required (PHONE or EMAIL)")
    OtpType type,

    @NotBlank(message = "code is required")
    @Pattern(regexp = "^\\d{6}$", message = "code must be 6 digits")
    String code
) {}
```

---

## User Entity — Key Fields

```java
@Id
@GeneratedValue(strategy = GenerationType.UUID)
private UUID id;

@Column(name = "first_name", nullable = false, length = 100)
private String firstName;

@Column(name = "last_name", nullable = false, length = 100)
private String lastName;

@Column(nullable = false, unique = true, length = 20)
private String phone;       // E.164, always present

@Column(unique = true, length = 255)
private String email;       // nullable, set at registration or later via PUT /users/me

@Column(nullable = false, length = 255)
private String password;    // BCrypt hashed

@Enumerated(EnumType.STRING)
@Column(nullable = false, length = 20)
@Builder.Default
private Role role = Role.USER;

@Column(nullable = false)
@Builder.Default
private Boolean enabled = true;   // future account suspension support

// NEW
@Column(name = "phone_verified", nullable = false)
@Builder.Default
private boolean phoneVerified = false;   // flipped true by OtpService.verify(PHONE)

// NEW
@Column(name = "email_verified", nullable = false)
@Builder.Default
private boolean emailVerified = false;   // flipped true by OtpService.verify(EMAIL)

@Column(name = "created_at", nullable = false, updatable = false)
private OffsetDateTime createdAt;

@Column(name = "updated_at", nullable = false)
private OffsetDateTime updatedAt;
```

> `getUsername()` (from `UserDetails`) returns `id.toString()`, not `phone` or `email` — keeps Spring Security's internal identity decoupled from the actual login fields.
> `JwtFilter` sets `Authentication#getName()` to this same `id.toString()`; `SecurityUtils.currentUserId()` (new) relies on that to resolve the caller in `OtpController`.

---

## OTP Delivery — Migration Path to a Real Provider

```java
public interface OtpSender {
    void send(User user, OtpType type, String plainCode);
}
```

- `LoggingOtpSender` is the **dev-only** implementation, active by default
  (`otp.sender=logging` or unset). It logs the plaintext code at `WARN` level
  and never touches the database with it — only a BCrypt hash is persisted.
- To plug in Twilio (or AWS SNS, SES, etc.) later: implement `OtpSender` in a
  new class (e.g. `TwilioOtpSender`), guard it with
  `@ConditionalOnProperty(name = "otp.sender", havingValue = "twilio")`, and
  flip `otp.sender=twilio` in `application-prod.yml` / env vars.
  `OtpService`, `OtpController`, and the Flyway schema are untouched — this
  is the entire reason the interface exists.

---

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

### Phase 3 — User Profile

- [ ] `GET /users/me`
- [ ] `PUT /users/me` (update firstName/lastName and/or add/change email)
- [ ] `PUT /users/me/password`

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
