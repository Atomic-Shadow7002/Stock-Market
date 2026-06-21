# Stock Market Backend

> Java 21 · Spring Boot 3 · PostgreSQL 17 · Flyway · Docker · JWT

---

## Tech Stack

| Layer            | Technology              |
| ---------------- | ----------------------- |
| Language         | Java 21                 |
| Framework        | Spring Boot 3           |
| Security         | Spring Security + JWT   |
| Database         | PostgreSQL 17           |
| Migrations       | Flyway                  |
| Containerization | Docker & Docker Compose |
| Build Tool       | Maven                   |

---

## Auth Strategy

| Method           | Status    | Identifier       |
| ---------------- | --------- | ---------------- |
| Phone + Password | ✅ MVP    | Phone (required) |
| Email + Password | ✅ MVP    | Email (optional) |
| Phone + OTP      | 🔜 Future | —                |

> **Phone is always required.** Email is optional and can be added later by the user.
> Login can be done via phone or email, but registration always requires phone.

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
    │   │   │   └── JacksonConfig.java
    │   │   │
    │   │   ├── exception/
    │   │   │   ├── GlobalExceptionHandler.java
    │   │   │   ├── ResourceNotFoundException.java
    │   │   │   └── DuplicateResourceException.java
    │   │   │
    │   │   ├── response/
    │   │   │   └── ApiResponse.java
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
    │   │   │   ├── RegisterRequest.java
    │   │   │   └── AuthResponse.java
    │   │   │
    │   │   ├── user/
    │   │   │   ├── User.java
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
    │       ├── application.yml
    │       └── db/
    │           └── migration/
    │               ├── V1__create_users.sql
    │               ├── V2__create_refresh_tokens.sql
    │               └── V3__create_watchlists.sql
    │
    └── test/
        └── java/com/luffy/trading/
            ├── auth/
            │   └── AuthServiceTest.java
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

## Database Migrations

```sql
-- V1__create_users.sql
CREATE TABLE users (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    phone       VARCHAR(15)  NOT NULL UNIQUE,              -- required, E.164 e.g. +919876543210
    email       VARCHAR(255) UNIQUE,                       -- optional
    password    VARCHAR(255) NOT NULL,
    name        VARCHAR(100) NOT NULL,
    role        VARCHAR(20)  NOT NULL DEFAULT 'USER',
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT now()
);

-- V2__create_refresh_tokens.sql
CREATE TABLE refresh_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token       TEXT         NOT NULL UNIQUE,
    expires_at  TIMESTAMP    NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT now()
);

-- V3__create_watchlists.sql
CREATE TABLE watchlists (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name        VARCHAR(100) NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE watchlist_items (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    watchlist_id UUID         NOT NULL REFERENCES watchlists(id) ON DELETE CASCADE,
    symbol       VARCHAR(20)  NOT NULL,
    exchange     VARCHAR(10)  NOT NULL,
    added_at     TIMESTAMP    NOT NULL DEFAULT now(),
    UNIQUE (watchlist_id, symbol)
);
```

---

## API Endpoints

### Auth

| Method | Endpoint         | Auth | Description                     |
| ------ | ---------------- | ---- | ------------------------------- |
| POST   | `/auth/register` | No   | Create account (phone required) |
| POST   | `/auth/login`    | No   | Login via phone or email        |
| POST   | `/auth/refresh`  | No   | Refresh access token            |
| POST   | `/auth/logout`   | Yes  | Revoke refresh token            |

#### `POST /auth/register` — Request Body

```json
{
  "phone": "+919876543210",
  "password": "secret123",
  "name": "Abhi",
  "email": "abhi@example.com"
}
```

> `phone`, `password`, `name` are required. `email` is optional.

#### `POST /auth/login` — Request Body

```json
{ "phone": "+919876543210", "password": "secret123" }
```

```json
{ "email": "abhi@example.com", "password": "secret123" }
```

> Either `phone` or `email` must be provided. `phone` takes priority if both are sent.

### User

| Method | Endpoint             | Auth | Description         |
| ------ | -------------------- | ---- | ------------------- |
| GET    | `/users/me`          | Yes  | Get own profile     |
| PUT    | `/users/me`          | Yes  | Update name / email |
| PUT    | `/users/me/password` | Yes  | Change password     |

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
  body: { phone* , password*, name*, email? }
        │
        ▼
  validate: phone present + E.164 format
  check: phone not already registered
  check: email not already registered (if provided)
        │
        ▼
  BCrypt(password) → save User
        │
        ▼
  generate accessToken (sub = userId, 15 min)
  save refreshToken → DB (7 days)
        │
        ▼
  return { accessToken, refreshToken }


POST /auth/login
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

---

## Request / Response Contracts

### RegisterRequest.java

```java
public record RegisterRequest(
    @NotBlank @Pattern(regexp = "^\\+[1-9]\\d{7,14}$", message = "Phone must be E.164 format")
    String phone,           // required

    @NotBlank
    String password,        // required

    @NotBlank
    String name,            // required

    @Email
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
    String phone,
    String email,           // nullable
    String name,
    String role,
    LocalDateTime createdAt
) {}
```

---

## User Entity — Key Fields

```java
@Column(nullable = false, unique = true)
private String phone;       // E.164, always present

@Column(unique = true)
private String email;       // nullable, set at registration or later via PUT /users/me

@Column(nullable = false)
private String password;    // BCrypt hashed

@Column(nullable = false)
private String name;
```

---

## Build Order

### Phase 1 — Foundation ✅

- [x] Folder structure
- [x] `application.yml`
- [x] `docker-compose.yml`
- [] `ApiResponse<T>`
- [] `GlobalExceptionHandler`
- [] Flyway + `V1__create_users.sql` (phone required, email optional)

### Phase 2 — Auth

- [ ] `User` entity (`phone` NOT NULL, `email` nullable), `Role` enum, `UserRepository`
- [ ] `PasswordEncoder` bean (BCrypt)
- [ ] `JwtService` — generate + validate tokens (sub = userId)
- [ ] `JwtFilter` — validate Bearer token on every request
- [ ] `SecurityConfig` — permit `/auth/**`, lock everything else
- [ ] `RegisterRequest` — phone + password + name required, email optional
- [ ] `LoginRequest` — phone or email + password
- [ ] `AuthController` — register, login, refresh, logout
- [ ] `RefreshToken` entity + `V2__create_refresh_tokens.sql`

### Phase 3 — User Profile

- [ ] `GET /users/me`
- [ ] `PUT /users/me` (update name and/or add/change email)
- [ ] `PUT /users/me/password`

### Phase 4 — Market Data

- [ ] `SmartApiClient` — HTTP calls to Angel SmartAPI
- [ ] `SmartApiProperties`
- [ ] `MarketController` — quote, search

### Phase 5 — Watchlists

- [ ] `V3__create_watchlists.sql`
- [ ] `Watchlist` + `WatchlistItem` entities
- [ ] `WatchlistController` — CRUD + items

### Later (not now)

- Phone + OTP login (Twilio / AWS SNS) — schema already ready
- Swagger / OpenAPI
- WebSocket
- Redis
- Portfolio simulator
- `application-prod.yml` + deployment

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
