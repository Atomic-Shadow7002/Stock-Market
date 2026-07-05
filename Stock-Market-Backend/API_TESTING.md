# API Testing Guide — Register → Admin (Phase 1 → Phase 3)

Walks every endpoint in order, using `curl`. Run each block top to bottom —
later steps reuse tokens/ids from earlier ones. Swap in `jq` if you want to
avoid copy-pasting values by hand; commands to extract fields with `jq` are
included as an alternative under each step.

## 0. Start the app

```bash
docker compose up -d
./mvnw spring-boot:run
```

Watch the startup logs for this line (first boot only):

```
Bootstrapped first ADMIN account with phone +910000000001 — change its password via the app as soon as possible.
```

That's your seeded admin (phone `+910000000001`, password `ChangeMe@123` by
default — override via `ADMIN_SEED_PHONE` / `ADMIN_SEED_PASSWORD` env vars
before first boot if you want different values).

---

## How the seeded admin actually gets created (read this first)

There is no separate "create admin" command to run — it happens
automatically, once, the first time the app boots against a database with
zero `ADMIN`-role users. Mechanically:

1. Flyway runs your migrations (including `V5`, which is unrelated to this
   but must run first regardless).
2. `AdminSeeder` (an `ApplicationRunner`) runs right after the context
   starts up.
3. It calls `userRepository.existsByRole(Role.ADMIN)`.
   - If **true** (an admin already exists, from a previous boot) → does
     nothing. This makes it safe to restart the app repeatedly without
     ever creating a second seeded admin.
   - If **false** → reads `admin.seed.phone` / `admin.seed.password` (and
     optionally `first-name`/`last-name`/`email`) from `application.yml`
     (which themselves read `ADMIN_SEED_PHONE` / `ADMIN_SEED_PASSWORD` /
     etc. env vars, falling back to the dev defaults below if unset),
     hashes the password with the real `PasswordEncoder` bean, and saves a
     `User` with `role=ADMIN`, `enabled=true`, `phoneVerified=true`.

**Dev defaults** (used if you don't set the env vars):

| Field     | Value           |
| --------- | --------------- |
| phone     | `+910000000001` |
| password  | `ChangeMe@123`  |
| firstName | `Admin`         |
| lastName  | `User`          |
| email     | _(none)_        |

**To use different values instead of the defaults**, set these before the
_first_ boot against a fresh database (they're ignored on every boot after
that, since an admin will already exist):

```bash
export ADMIN_SEED_PHONE="+919999999999"
export ADMIN_SEED_PASSWORD="SomeStrongerPassword123"
./mvnw spring-boot:run
```

**How to confirm it worked** — either watch for the log line shown above, or
query the database directly:

```bash
docker compose exec postgres psql -U postgres -d trading \
  -c "SELECT phone, role, enabled, phone_verified FROM users WHERE role='ADMIN';"
```

**How to log in as the seeded admin** — it's the exact same `/auth/login`
endpoint every other user uses; no OTP step is needed because
`phoneVerified` starts `true` for the seed account:

```bash
curl -s -X POST localhost:8080/auth/login -H 'Content-Type: application/json' \
  -d '{"phone":"+910000000001","password":"ChangeMe@123"}'
```

Copy `data.accessToken` from the response → this is your `$ADMIN_TOKEN` for
every admin request below (step 9 repeats this once you've also created a
normal user, in case you want to test both together).

**If you already have a database from before Phase 3** (e.g. you ran
Phase 1/2 testing earlier and never wiped the volume): just restart the app
— Flyway applies `V5` automatically, and since that existing database has
zero `ADMIN` rows, `AdminSeeder` will create one on that same boot. No
migration or manual step is needed beyond starting the app normally.

**If you want a completely fresh start:**

```bash
docker compose down -v && docker compose up -d
./mvnw spring-boot:run
```

---

## 1. Register a normal user

```bash
curl -s -X POST localhost:8080/auth/register -H 'Content-Type: application/json' \
  -d '{"firstName":"Abhi","lastName":"Sharma","phone":"+919876543210","password":"secret123","email":"abhi@example.com"}'
```

Copy `data.accessToken` from the response → `$USER_TOKEN`.
Copy `data.refreshToken` → `$USER_REFRESH`.

Watch the app logs for a line from `LoggingOtpSender` — that's your 6-digit
phone OTP → `$PHONE_OTP`.

_Try logging in now — it will correctly fail, since the phone isn't verified yet (step 3 fixes that)._

## 2. Confirm login is blocked pre-verification

```bash
curl -s -X POST localhost:8080/auth/login -H 'Content-Type: application/json' \
  -d '{"phone":"+919876543210","password":"secret123"}'
```

Expect `401` — `"Phone number not verified — verify via /otp/verify before logging in"`.

## 3. Verify the phone OTP

```bash
curl -s -X POST localhost:8080/otp/verify -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $USER_TOKEN" \
  -d "{\"type\":\"PHONE\",\"code\":\"$PHONE_OTP\"}"
```

Expect `200 OK`.

## 4. Log in for real

```bash
curl -s -X POST localhost:8080/auth/login -H 'Content-Type: application/json' \
  -d '{"phone":"+919876543210","password":"secret123"}'
```

Update `$USER_TOKEN` / `$USER_REFRESH` from this response (fresh tokens).

## 5. Refresh + logout (sanity check, optional)

```bash
curl -s -X POST localhost:8080/auth/refresh -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"$USER_REFRESH\"}"

curl -s -X POST localhost:8080/auth/logout -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"$USER_REFRESH\"}"
```

Logout invalidates `$USER_REFRESH` — log in again (step 4) to get a fresh
pair before continuing, since the next sections need a working `$USER_TOKEN`.

---

## 6. Get your own profile — `GET /users/me`

```bash
curl -s localhost:8080/users/me -H "Authorization: Bearer $USER_TOKEN"
```

Expect your profile, with `"role":"USER"`, `"phoneVerified":true`.

## 7. Update your profile — `PUT /users/me`

```bash
curl -s -X PUT localhost:8080/users/me -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $USER_TOKEN" \
  -d '{"firstName":"Abhishek","lastName":"Sharma","email":"abhishek@example.com"}'
```

Expect the updated profile. Note `emailVerified` is unchanged from before —
that's intentional (see the README/design notes on why).

## 8. Change your password — two steps

**8a. Request the OTP:**

```bash
curl -s -X POST localhost:8080/users/me/password/otp -H "Authorization: Bearer $USER_TOKEN"
```

Watch the logs again for a fresh 6-digit code → `$PWD_OTP` (this is a
_different_ OTP row than the phone-verification one — type `PASSWORD_RESET`).

**8b. Submit the code + new password:**

```bash
curl -s -X PUT localhost:8080/users/me/password -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $USER_TOKEN" \
  -d "{\"code\":\"$PWD_OTP\",\"newPassword\":\"newSecret456\"}"
```

Expect `200 OK`. Your old `$USER_REFRESH` is now revoked (password changes
kill all refresh tokens) — log in again with the new password to keep going:

```bash
curl -s -X POST localhost:8080/auth/login -H 'Content-Type: application/json' \
  -d '{"phone":"+919876543210","password":"newSecret456"}'
```

Update `$USER_TOKEN` from this response.

---

## 9. Log in as the seeded admin

```bash
curl -s -X POST localhost:8080/auth/login -H 'Content-Type: application/json' \
  -d '{"phone":"+910000000001","password":"ChangeMe@123"}'
```

Copy `data.accessToken` → `$ADMIN_TOKEN`.

## 10. Confirm a normal user is blocked from admin routes

```bash
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/admin/users \
  -H "Authorization: Bearer $USER_TOKEN"
```

Expect `403`.

## 11. List/search users — `GET /admin/users`

```bash
# everyone, paginated
curl -s "localhost:8080/admin/users?page=0&size=20" -H "Authorization: Bearer $ADMIN_TOKEN"

# filtered by (partial) phone
curl -s "localhost:8080/admin/users?phone=98765" -H "Authorization: Bearer $ADMIN_TOKEN"
```

Copy the `Abhishek`/`Abhi` user's `id` from either response → `$USER_ID`.

## 12. View a single user's full profile — `GET /admin/users/{id}`

```bash
curl -s "localhost:8080/admin/users/$USER_ID" -H "Authorization: Bearer $ADMIN_TOKEN"
```

## 13. Disable (suspend) that user — `PATCH /admin/users/{id}/status`

```bash
curl -s -X PATCH "localhost:8080/admin/users/$USER_ID/status" -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"enabled":false}'
```

Confirm the user can no longer log in:

```bash
curl -s -X POST localhost:8080/auth/login -H 'Content-Type: application/json' \
  -d '{"phone":"+919876543210","password":"newSecret456"}'
```

Expect `401` — `"Account is disabled"`.

## 14. Re-enable the user

```bash
curl -s -X PATCH "localhost:8080/admin/users/$USER_ID/status" -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"enabled":true}'
```

## 15. Confirm an admin can't disable themselves

```bash
curl -s -X PATCH "localhost:8080/admin/users/<ADMIN_OWN_ID>/status" -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"enabled":false}'
```

(Get the admin's own id from `GET /users/me` with `$ADMIN_TOKEN` if needed.)
Expect `400` — `"You cannot change your own account status"`.

## 16. Promote the user to ADMIN — `POST /admin/users/{id}/promote`

```bash
curl -s -X POST "localhost:8080/admin/users/$USER_ID/promote" -H "Authorization: Bearer $ADMIN_TOKEN"
```

Expect `"role":"ADMIN"` in the response. Note: this user's _existing_
`$USER_TOKEN` still has USER-level access until it expires or is refreshed —
`JwtFilter` re-reads the role from the DB on every request, so their very
next API call already carries admin privileges, no new login required.

Confirm it:

```bash
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/admin/users \
  -H "Authorization: Bearer $USER_TOKEN"
```

Expect `200` now (was `403` in step 10).

---

## Endpoint reference (all of the above, in one table)

| Method | Endpoint                    | Auth  | Notes                           |
| ------ | --------------------------- | ----- | ------------------------------- |
| POST   | `/auth/register`            | No    | fires phone OTP                 |
| POST   | `/auth/login`               | No    | blocked until phone verified    |
| POST   | `/auth/refresh`             | No    |                                 |
| POST   | `/auth/logout`              | Yes   |                                 |
| POST   | `/otp/send`                 | Yes   | `{type: PHONE\|EMAIL}`          |
| POST   | `/otp/verify`               | Yes   | `{type, code}`                  |
| GET    | `/users/me`                 | Yes   |                                 |
| PUT    | `/users/me`                 | Yes   | firstName/lastName/email        |
| POST   | `/users/me/password/otp`    | Yes   | step 1 of password change       |
| PUT    | `/users/me/password`        | Yes   | `{code, newPassword}` — step 2  |
| GET    | `/admin/users`              | Admin | `?phone=&page=&size=`           |
| GET    | `/admin/users/{id}`         | Admin |                                 |
| PATCH  | `/admin/users/{id}/status`  | Admin | `{enabled}` — can't target self |
| POST   | `/admin/users/{id}/promote` | Admin |                                 |
