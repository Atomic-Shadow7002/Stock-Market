# Stock Market Backend

A Spring Boot backend for a trading platform with JWT authentication, PostgreSQL, Flyway migrations, and Docker support.

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

## Prerequisites

- Java 21+
- Maven (or use the included `./mvnw` wrapper)
- Docker & Docker Compose

---

## Getting Started

### 1. Clone the repository

```bash
git clone <repository-url>
cd trading
```

### 2. Start PostgreSQL

```bash
docker compose up -d
```

Verify the container is running:

```bash
docker ps
```

### 3. Run the application

**Linux / macOS:**

```bash
./mvnw spring-boot:run
```

**Windows:**

```cmd
mvnw.cmd spring-boot:run
```

Or run `TradingApplication.java` directly from your IDE.

The application will start on `http://localhost:8080` by default.

---

## Database

Default connection settings (configured in `application.yml`):

| Setting  | Value      |
| -------- | ---------- |
| Database | `trading`  |
| Username | `postgres` |
| Password | `postgres` |
| Port     | `5432`     |

Migrations are managed automatically by Flyway on startup.

---

## Project Structure

```
src/
├── main/
│   ├── java/                        # Application source code
│   └── resources/
│       ├── application.yml          # App configuration
│       └── db/
│           └── migration/           # Flyway SQL migrations
└── test/                            # Unit and integration tests
```

---

## Build

```bash
# Compile the project
./mvnw clean compile

# Create the JAR
./mvnw clean package

# Run the JAR directly
java -jar target/trading-0.0.1-SNAPSHOT.jar
```

---

## Development Workflow

```bash
# 1. Start PostgreSQL
docker compose up -d

# 2. Run the application
./mvnw spring-boot:run

# 3. Stop the application
Ctrl + C

# 4. Stop PostgreSQL
docker compose down
```

---

## Stopping & Cleanup

```bash
# Stop the database container
docker compose down

# Stop and remove the database volume (resets all data)
docker compose down -v
```

---

## Authentication

This project uses JWT-based authentication via Spring Security. Secure your endpoints by including a valid `Authorization: Bearer <token>` header in your requests.
