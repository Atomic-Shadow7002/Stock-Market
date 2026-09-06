# API Testing Guide — Complete Backend + Angel One Integration

Full `curl`-based walkthrough for every endpoint. Run each block top-to-bottom — later
steps reuse tokens/ids captured from earlier ones. All examples assume both services are
running on their default ports (backend `:8080`, Angel One service `:8081`).

---

## Environment Setup

```bash
# Set these once at the start of your shell session.
# Fill in real values after each step that produces them.
export BASE=http://localhost:8080

# Placeholders — filled in as you work through the steps below
export USER_TOKEN=""
export USER_REFRESH=""
export PHONE_OTP=""
export USER_ID=""
export ADMIN_TOKEN=""
export PWD_OTP=""
```

---

## PART 1 — Auth & User (existing backend functionality)

### 0. Start everything

```bash
# Terminal 1: PostgreSQL
cd Stock-Market-Backend && docker compose up -d

# Terminal 2: Angel One service
cd angelone-market-service && ./mvnw spring-boot:run
# Wait for: "AngelOne login succeeded"

# Terminal 3: Backend
cd Stock-Market-Backend && ./mvnw spring-boot:run
# Wait for: "Bootstrapped first ADMIN account..."  (first boot only)
```

---

### 1. Register a normal user

```bash
curl -s -X POST $BASE/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"firstName":"Abhi","lastName":"Sharma","phone":"+919876543210","password":"secret123","email":"abhi@example.com"}'
```

Expected: `201 Created`, `data.accessToken` + `data.refreshToken`.

```bash
export USER_TOKEN="<paste data.accessToken here>"
export USER_REFRESH="<paste data.refreshToken here>"
```

Watch the app logs for a line from `LoggingOtpSender`:
```
OTP for user ... type PHONE: 123456
```
```bash
export PHONE_OTP="123456"  # paste the 6-digit code from logs
```

---

### 2. Confirm login is blocked before phone verification

```bash
curl -s -X POST $BASE/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"phone":"+919876543210","password":"secret123"}'
```

Expected: `401` — `"Phone number not verified"`.

---

### 3. Verify the phone OTP

```bash
curl -s -X POST $BASE/otp/verify \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $USER_TOKEN" \
  -d "{\"type\":\"PHONE\",\"code\":\"$PHONE_OTP\"}"
```

Expected: `200 OK`.

---

### 4. Login for real

```bash
curl -s -X POST $BASE/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"phone":"+919876543210","password":"secret123"}'
```

```bash
export USER_TOKEN="<fresh accessToken>"
export USER_REFRESH="<fresh refreshToken>"
```

---

### 5. Token refresh + logout (optional sanity check)

```bash
curl -s -X POST $BASE/auth/refresh \
  -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"$USER_REFRESH\"}"

curl -s -X POST $BASE/auth/logout \
  -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"$USER_REFRESH\"}"
```

Log back in (step 4) to get a fresh token before continuing.

---

### 6. Get own profile

```bash
curl -s $BASE/users/me -H "Authorization: Bearer $USER_TOKEN"
```

Expected: profile with `"role":"USER"`, `"phoneVerified":true`.

---

### 7. Update own profile

```bash
curl -s -X PUT $BASE/users/me \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $USER_TOKEN" \
  -d '{"firstName":"Abhishek","lastName":"Sharma","email":"abhishek@example.com"}'
```

---

### 8. Change password (two-step)

**8a. Request the OTP:**
```bash
curl -s -X POST $BASE/users/me/password/otp \
  -H "Authorization: Bearer $USER_TOKEN"
```

Watch logs: `OTP for user ... type PASSWORD_RESET: 654321`

```bash
export PWD_OTP="654321"
```

**8b. Submit the code + new password:**
```bash
curl -s -X PUT $BASE/users/me/password \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $USER_TOKEN" \
  -d "{\"code\":\"$PWD_OTP\",\"newPassword\":\"newSecret456\"}"
```

Log back in with the new password:
```bash
curl -s -X POST $BASE/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"phone":"+919876543210","password":"newSecret456"}'
export USER_TOKEN="<new accessToken>"
```

---

## PART 2 — Admin (User Management)

### 9. Login as the seeded admin

```bash
curl -s -X POST $BASE/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"phone":"+910000000001","password":"ChangeMe@123"}'
```

(Or use the phone/password from your `.env`'s `ADMIN_SEED_*` vars.)

```bash
export ADMIN_TOKEN="<data.accessToken>"
```

---

### 10. Confirm a normal user is blocked from admin routes

```bash
curl -s -o /dev/null -w '%{http_code}\n' $BASE/admin/users \
  -H "Authorization: Bearer $USER_TOKEN"
```

Expected: `403`.

---

### 11. List users (paginated / filtered)

```bash
# All users
curl -s "$BASE/admin/users?page=0&size=20" \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# Filter by phone substring
curl -s "$BASE/admin/users?phone=98765" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

```bash
export USER_ID="<paste the test user's id here>"
```

---

### 12. View a single user

```bash
curl -s "$BASE/admin/users/$USER_ID" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

---

### 13. Disable a user

```bash
curl -s -X PATCH "$BASE/admin/users/$USER_ID/status" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"enabled":false}'
```

Confirm login fails:
```bash
curl -s -X POST $BASE/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"phone":"+919876543210","password":"newSecret456"}'
```
Expected: `401` — `"Account is disabled"`.

---

### 14. Re-enable the user

```bash
curl -s -X PATCH "$BASE/admin/users/$USER_ID/status" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"enabled":true}'
```

---

### 15. Admin can't disable themselves

```bash
# Get admin's own id
curl -s $BASE/users/me -H "Authorization: Bearer $ADMIN_TOKEN"

curl -s -X PATCH "$BASE/admin/users/<ADMIN_OWN_ID>/status" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"enabled":false}'
```

Expected: `400` — `"You cannot change your own account status"`.

---

### 16. Promote user to ADMIN

```bash
curl -s -X POST "$BASE/admin/users/$USER_ID/promote" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

Confirm immediate effect (no re-login needed):
```bash
curl -s -o /dev/null -w '%{http_code}\n' $BASE/admin/users \
  -H "Authorization: Bearer $USER_TOKEN"
```

Expected: `200` (was `403` in step 10).

---

## PART 3 — Instrument Lookup

These endpoints work for any authenticated user. They proxy the Angel One service's
in-memory instrument index (refreshed daily from AngelOne's scrip master dump).

### 17. Resolve a symbol to an instrument record

```bash
curl -s "$BASE/instruments/resolve?exchange=NSE&symbol=SBIN-EQ" \
  -H "Authorization: Bearer $USER_TOKEN"
```

Expected: `{ success:true, data: { found:true, instrument:{ token:"3045", symbol:"SBIN-EQ", ... } } }`

---

### 18. Reverse lookup: token → instrument

```bash
curl -s "$BASE/instruments/token?exchange=NSE&token=3045" \
  -H "Authorization: Bearer $USER_TOKEN"
```

---

### 19. Search instruments by name/symbol

```bash
# Prefix match (fast path)
curl -s "$BASE/instruments/search?query=SBIN&exchange=NSE" \
  -H "Authorization: Bearer $USER_TOKEN"

# Cross-exchange search
curl -s "$BASE/instruments/search?query=NIFTY&limit=5" \
  -H "Authorization: Bearer $USER_TOKEN"

# With limit
curl -s "$BASE/instruments/search?query=RELIANCE&exchange=NSE&limit=3" \
  -H "Authorization: Bearer $USER_TOKEN"
```

---

### 20. Instrument index status

```bash
curl -s "$BASE/instruments/status" \
  -H "Authorization: Bearer $USER_TOKEN"
```

Expected:
```json
{
  "success": true,
  "data": {
    "loaded": true,
    "count": 245000,
    "source": "live",
    "lastUpdated": "2026-08-31T03:30:00Z",
    "stale": false
  }
}
```

`stale:true` means the snapshot hasn't been refreshed in over 30 hours (one missed daily cycle).
Fix it with step 27 (`POST /admin/angelone/instruments/refresh`).

---

## PART 4 — Market Data

These endpoints require a valid JWT. They proxy the Angel One service and return cached data.
The `data` field in each response is exactly what AngelOne returns.

> **Prerequisite:** Confirm the Angel One service is logged in before calling these:
> ```bash
> curl -s "$BASE/admin/angelone/session/status" -H "Authorization: Bearer $ADMIN_TOKEN"
> ```

---

### 21. Real-time quote

```bash
# LTP by symbol (no token needed)
curl -s "$BASE/market/quote?mode=LTP&exchange=NSE&symbols=SBIN-EQ,RELIANCE-EQ" \
  -H "Authorization: Bearer $USER_TOKEN"

# FULL quote by token
curl -s "$BASE/market/quote?mode=FULL&exchange=NSE&tokens=3045,2885" \
  -H "Authorization: Bearer $USER_TOKEN"

# OHLC by symbol
curl -s "$BASE/market/quote?mode=OHLC&exchange=NSE&symbols=SBIN-EQ" \
  -H "Authorization: Bearer $USER_TOKEN"
```

---

### 22. Historical candles (OHLCV)

```bash
# By symbol
curl -s "$BASE/market/candles?exchange=NSE&symbol=SBIN-EQ&interval=ONE_DAY&fromDate=2026-07-01+09:15&toDate=2026-08-01+15:30" \
  -H "Authorization: Bearer $USER_TOKEN"

# By token
curl -s "$BASE/market/candles?exchange=NSE&symbolToken=3045&interval=ONE_MINUTE&fromDate=2026-08-31+09:15&toDate=2026-08-31+10:00" \
  -H "Authorization: Bearer $USER_TOKEN"
```

**Valid intervals:** `ONE_MINUTE`, `FIVE_MINUTE`, `FIFTEEN_MINUTE`, `THIRTY_MINUTE`, `ONE_HOUR`, `ONE_DAY`  
**Date format:** `yyyy-MM-dd HH:mm` (URL-encode the space as `+` or `%20`)

---

### 23. Option Greeks

```bash
curl -s "$BASE/market/greeks?name=NIFTY&expiryDate=28AUG2025" \
  -H "Authorization: Bearer $USER_TOKEN"

curl -s "$BASE/market/greeks?name=BANKNIFTY&expiryDate=27AUG2025" \
  -H "Authorization: Bearer $USER_TOKEN"
```

---

### 24. Brokerage estimate

Calculates charges for a hypothetical order basket. Does **not** place any order.

```bash
# With token known
curl -s -X POST $BASE/market/brokerage \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $USER_TOKEN" \
  -d '{
    "orders": [{
      "product_type": "DELIVERY",
      "transaction_type": "BUY",
      "quantity": "10",
      "price": "800",
      "exchange": "NSE",
      "symbol_name": "SBIN-EQ",
      "token": "3045"
    }]
  }'

# Without token — resolved from exchange + symbol_name automatically
curl -s -X POST $BASE/market/brokerage \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $USER_TOKEN" \
  -d '{
    "orders": [{
      "product_type": "INTRADAY",
      "transaction_type": "SELL",
      "quantity": "5",
      "price": "2900",
      "exchange": "NSE",
      "symbol_name": "RELIANCE-EQ"
    }]
  }'
```

---

### 25. Margin calculation

Calculates real-time margin required for a hypothetical position basket. Does **not** place any order. Up to 50 positions.

```bash
# With token
curl -s -X POST $BASE/market/margin \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $USER_TOKEN" \
  -d '{
    "positions": [{
      "exchange": "NFO",
      "qty": 50,
      "price": 0,
      "productType": "INTRADAY",
      "token": "67300",
      "tradeType": "BUY",
      "orderType": "MARKET"
    }]
  }'

# Without token — resolved from exchange + symbol
curl -s -X POST $BASE/market/margin \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $USER_TOKEN" \
  -d '{
    "positions": [{
      "exchange": "NFO",
      "qty": 50,
      "price": 0,
      "productType": "INTRADAY",
      "symbol": "NIFTY28AUG25FUT",
      "tradeType": "BUY",
      "orderType": "MARKET"
    }]
  }'
```

---

### 26. Historical Open Interest

F&O instruments only (NFO/BFO). Cash-market symbols have no OI.

```bash
# By symbol
curl -s "$BASE/market/oi?exchange=NFO&symbol=NIFTY28AUG25FUT&interval=THREE_MINUTE&fromDate=2026-08-06+11:15&toDate=2026-08-06+12:00" \
  -H "Authorization: Bearer $USER_TOKEN"

# By token
curl -s "$BASE/market/oi?exchange=NFO&symbolToken=46823&interval=ONE_HOUR&fromDate=2026-08-01+09:15&toDate=2026-08-06+15:30" \
  -H "Authorization: Bearer $USER_TOKEN"
```

**Valid intervals:** `ONE_MINUTE`, `THREE_MINUTE`, `FIVE_MINUTE`, `TEN_MINUTE`, `FIFTEEN_MINUTE`, `THIRTY_MINUTE`, `ONE_HOUR`, `ONE_DAY`

---

### 27. Intraday eligible scrips

```bash
curl -s "$BASE/market/intraday-eligible?exchange=NSE" \
  -H "Authorization: Bearer $USER_TOKEN"

curl -s "$BASE/market/intraday-eligible?exchange=BSE" \
  -H "Authorization: Bearer $USER_TOKEN"
```

Cached for 6 hours by the Angel One service. Contains scrip list + margin multipliers.

---

### 28. Cautionary scrips (ASM/GSM)

```bash
curl -s $BASE/market/cautionary \
  -H "Authorization: Bearer $USER_TOKEN"
```

Cached for 1 hour. No parameters — always the full current list.

---

## PART 5 — Admin: Angel One Service Management

These endpoints require ADMIN role. Use `$ADMIN_TOKEN`.

---

### 29. Check session status

```bash
curl -s "$BASE/admin/angelone/session/status" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

Expected: `{ "success": true, "data": { "loggedIn": true } }`

If `loggedIn: false`, the market-data endpoints will return 503. Use step 30 to fix.

---

### 30. Force re-login (break-glass)

Use when the session is unexpectedly invalid (e.g. AngelOne revoked it server-side).
The Angel One service uses its configured TOTP secret — no credentials needed here.

```bash
curl -s -X POST "$BASE/admin/angelone/session/relogin" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

Expected: `{ "success": true, "data": { "status": true, "message": "re-login successful" } }`

---

### 31. Force instrument index refresh

```bash
curl -s -X POST "$BASE/admin/angelone/instruments/refresh" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

Expected: updated status with `"source":"live"`, fresh `lastUpdated`, `"stale":false`.
This fetches hundreds of thousands of rows from AngelOne — takes a few seconds.

---

## PART 6 — Error & Edge Cases

### 32. Unauthenticated access to protected endpoints

```bash
# No Authorization header at all
curl -s -o /dev/null -w '%{http_code}\n' $BASE/market/quote?mode=LTP&exchange=NSE&symbols=SBIN-EQ
```
Expected: `401`.

```bash
# Malformed / expired token
curl -s -o /dev/null -w '%{http_code}\n' $BASE/market/quote?mode=LTP&exchange=NSE&symbols=SBIN-EQ \
  -H "Authorization: Bearer invalidtoken123"
```
Expected: `401`.

---

### 33. User accessing admin-only endpoints

```bash
curl -s -o /dev/null -w '%{http_code}\n' "$BASE/admin/angelone/session/status" \
  -H "Authorization: Bearer $USER_TOKEN"
```
Expected: `403`.

---

### 34. Invalid symbol resolution

```bash
# Symbol that doesn't exist
curl -s "$BASE/market/quote?mode=LTP&exchange=NSE&symbols=NONEXISTENT-SYM" \
  -H "Authorization: Bearer $USER_TOKEN"
```

Expected: `400` — the Angel One service returns an error when symbol resolution fails.

---

### 35. Neither token nor symbol provided

```bash
curl -s "$BASE/market/quote?mode=LTP&exchange=NSE" \
  -H "Authorization: Bearer $USER_TOKEN"
```

Expected: `400` — `"Provide either 'tokens' or 'symbols'"`.

---

### 36. Angel One service unreachable

Stop the Angel One service (`Ctrl+C` in its terminal), then:

```bash
curl -s "$BASE/market/quote?mode=LTP&exchange=NSE&symbols=SBIN-EQ" \
  -H "Authorization: Bearer $USER_TOKEN"
```

Expected: `503` — `"Angel One market service is currently unavailable"`.  
Auth endpoints (`/auth/**`, `/users/**`, `/admin/users/**`) continue to work normally.

---

### 37. Invalid exchange for intraday-eligible

```bash
curl -s "$BASE/market/intraday-eligible?exchange=NFO" \
  -H "Authorization: Bearer $USER_TOKEN"
```

Expected: `400` — `"exchange must be NSE or BSE for /market/intraday-eligible"`.

---

### 38. Registration rate limit (5 attempts/hour/IP)

```bash
for i in {1..6}; do
  curl -s -o /dev/null -w "Attempt $i: %{http_code}\n" \
    -X POST $BASE/auth/register \
    -H 'Content-Type: application/json' \
    -d "{\"firstName\":\"Test\",\"lastName\":\"User\",\"phone\":\"+91900000000$i\",\"password\":\"test1234\"}"
done
```

Expected: first 5 return `201`, 6th returns `429 Too Many Requests`.

---

## Endpoint Reference (complete)

| Method | Endpoint                              | Auth         | Description                          |
| ------ | ------------------------------------- | ------------ | ------------------------------------ |
| POST   | `/auth/register`                      | None         | Register (fires phone OTP)           |
| POST   | `/auth/login`                         | None         | Login (post-verification only)       |
| POST   | `/auth/refresh`                       | None         | Refresh access token                 |
| POST   | `/auth/logout`                        | None         | Revoke refresh token                 |
| POST   | `/otp/send`                           | JWT          | Send OTP `{type:PHONE\|EMAIL}`       |
| POST   | `/otp/verify`                         | JWT          | Verify OTP `{type, code}`            |
| GET    | `/users/me`                           | JWT          | Get own profile                      |
| PUT    | `/users/me`                           | JWT          | Update own profile                   |
| POST   | `/users/me/password/otp`              | JWT          | Request password-change OTP          |
| PUT    | `/users/me/password`                  | JWT          | Change password `{code, newPassword}`|
| GET    | `/instruments/resolve`                | JWT          | Symbol → instrument                  |
| GET    | `/instruments/token`                  | JWT          | Token → instrument (reverse)         |
| GET    | `/instruments/search`                 | JWT          | Search instruments                   |
| GET    | `/instruments/status`                 | JWT          | Instrument index health              |
| GET    | `/market/quote`                       | JWT          | Real-time quote                      |
| GET    | `/market/candles`                     | JWT          | Historical OHLCV                     |
| GET    | `/market/greeks`                      | JWT          | Option Greeks                        |
| POST   | `/market/brokerage`                   | JWT          | Brokerage estimate (no order)        |
| POST   | `/market/margin`                      | JWT          | Margin calculation (no order)        |
| GET    | `/market/oi`                          | JWT          | Historical open interest             |
| GET    | `/market/intraday-eligible`           | JWT          | NSE/BSE intraday eligible list       |
| GET    | `/market/cautionary`                  | JWT          | ASM/GSM cautionary scrips            |
| GET    | `/admin/users`                        | JWT+ADMIN    | List/search users                    |
| GET    | `/admin/users/{id}`                   | JWT+ADMIN    | Get user by ID                       |
| PATCH  | `/admin/users/{id}/status`            | JWT+ADMIN    | Enable/disable user                  |
| POST   | `/admin/users/{id}/promote`           | JWT+ADMIN    | Promote to ADMIN                     |
| GET    | `/admin/angelone/session/status`      | JWT+ADMIN    | Angel One session health             |
| POST   | `/admin/angelone/session/relogin`     | JWT+ADMIN    | Force AngelOne re-login              |
| POST   | `/admin/angelone/instruments/refresh` | JWT+ADMIN    | Force instrument index refresh       |
