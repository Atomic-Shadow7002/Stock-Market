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
    email       VARCHAR(255) NOT NULL UNIQUE,
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

| Method | Endpoint         | Auth | Description          |
| ------ | ---------------- | ---- | -------------------- |
| POST   | `/auth/register` | No   | Create account       |
| POST   | `/auth/login`    | No   | Get tokens           |
| POST   | `/auth/refresh`  | No   | Refresh access token |
| POST   | `/auth/logout`   | Yes  | Revoke refresh token |

### User

| Method | Endpoint             | Auth | Description     |
| ------ | -------------------- | ---- | --------------- |
| GET    | `/users/me`          | Yes  | Get own profile |
| PUT    | `/users/me`          | Yes  | Update name     |
| PUT    | `/users/me/password` | Yes  | Change password |

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
POST /auth/register   →  BCrypt hash password, save user
POST /auth/login      →  verify password, return accessToken (15min) + refreshToken (7d)
GET  /users/me        →  JwtFilter validates Bearer token, sets SecurityContext
POST /auth/refresh    →  validate refreshToken in DB, return new accessToken
POST /auth/logout     →  delete refreshToken from DB
```

---

## Build Order

### Phase 1 — Foundation

- [ ] Folder structure
- [ ] `application.yml`
- [ ] `docker-compose.yml`
- [ ] `ApiResponse<T>`
- [ ] `GlobalExceptionHandler`
- [ ] Flyway + `V1__create_users.sql`

### Phase 2 — Auth

- [ ] `User` entity, `Role` enum, `UserRepository`
- [ ] `PasswordEncoder` bean (BCrypt)
- [ ] `JwtService` — generate + validate tokens
- [ ] `JwtFilter` — validate on every request
- [ ] `SecurityConfig` — permit `/auth/**`, lock everything else
- [ ] `AuthController` — register, login, refresh, logout
- [ ] `RefreshToken` entity + `V2__create_refresh_tokens.sql`

### Phase 3 — User Profile

- [ ] `GET /users/me`
- [ ] `PUT /users/me`
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
