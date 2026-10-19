# GameFlix User Service: Login & Registration (Module 4)

A Spring Boot service that registers users, hashes their passwords with bcrypt, issues a signed JWT on login, and uses that token to protect `GET /api/users/me`. Users are stored in MySQL.

**Stack:** Java 21 (compile target; also tested running on JDK 24), Spring Boot 3.5, Spring Security 6 (OAuth2 Resource Server for JWT), Spring Data JPA, MySQL 8.

## Endpoints

| Method | Path | Auth | Success | Failure |
|---|---|---|---|---|
| POST | `/api/auth/register` | none | 201 + `{userId, email, username, createdAt}` | 400 invalid input, 409 email already registered |
| POST | `/api/auth/login` | none | 200 + `{accessToken, tokenType, expiresIn, userId}` | 401 `"Invalid email or password"` for every failure |
| GET | `/api/users/me` | `Authorization: Bearer <token>` | 200 + profile | 401 missing, invalid, tampered, or expired token |

## How to run

### 1. MySQL setup (MySQL Workbench, as root)

1. Open `db/setup.sql`, replace `CHANGE_ME_DB_PASSWORD` with a local password, and run it. This creates:
   - database **`gameflix_users`**
   - user **`gameflix_app@localhost`** with only `SELECT, INSERT, UPDATE, DELETE` on that database
2. Run `db/schema.sql` to create the `users` table.

### 2. Local secrets

Copy `application.properties.example` to `src/main/resources/application-local.properties` and fill in:
- `spring.datasource.password`: the password from step 1
- `app.jwt.secret`: a Base64 string of 32 random bytes (commands are in the example file)

`application-local.properties` is in `.gitignore` and is never committed. Environment variables (`SPRING_DATASOURCE_PASSWORD`, `APP_JWT_SECRET`, etc.) work as an alternative.

### 3. Start the service

From the project folder:

```
.\mvnw spring-boot:run        (Windows)
./mvnw spring-boot:run        (macOS/Linux)
```
The service listens on `http://localhost:8080`.

## Schema

From `db/schema.sql` (also see the `SHOW CREATE TABLE users;` screenshot in the Word document):

```sql
CREATE TABLE users (
  user_id        BIGINT        NOT NULL AUTO_INCREMENT,
  email          VARCHAR(255)  NOT NULL,
  username       VARCHAR(50)   NOT NULL,
  password_hash  VARCHAR(255)  NOT NULL,
  first_name     VARCHAR(50)   NOT NULL,
  last_name      VARCHAR(50)   NOT NULL,
  avatar_url     VARCHAR(500)  NULL,
  bio            VARCHAR(500)  NULL,
  role           VARCHAR(20)   NOT NULL DEFAULT 'USER',
  enabled        BOOLEAN       NOT NULL DEFAULT TRUE,
  last_login_at  TIMESTAMP     NULL,
  created_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id),
  CONSTRAINT uk_users_email    UNIQUE (email),
  CONSTRAINT uk_users_username UNIQUE (username)
);
```

- `password_hash` is `VARCHAR(255)`. A `{bcrypt}` hash is 68 characters, so a 60-character column would silently truncate it and every login would fail.
- Email uniqueness is enforced by the `uk_users_email` constraint. Registration calls `saveAndFlush()` and turns the resulting `DataIntegrityViolationException` into the 409, so two simultaneous registrations can't both succeed. There is no "check if the email exists first" query to race against.
- Emails are trimmed and lowercased before saving and before login lookup.
- Column names follow my ERD (`user_id`, `password_hash`), so the hash-proof query is `SELECT user_id, email, password_hash FROM users;`.

## Design decisions

### Password hashing: bcrypt, work factor 12

I used **bcrypt** through Spring's `DelegatingPasswordEncoder`. Bcrypt is on the OWASP list of adaptive algorithms, ships with Spring Security with no extra dependency, and the delegating encoder stores an `{bcrypt}` prefix on each hash so the algorithm can be changed later without breaking existing logins. I raised the work factor from the default 10 to **12**. Each step doubles the cost, so offline guessing is about 4× slower for an attacker, while a single login on my machine still took about **700 ms** in my Postman test (request 04, including network and database time), which is an acceptable one-time wait at login.

Bcrypt only reads the first 72 bytes of a password, so registration limits passwords to 8–64 characters and also rejects anything over 72 bytes (possible with multi-byte characters).

Plaintext passwords are never stored, returned, or logged:
- The password is hashed in `AuthService` before it reaches the `User` entity.
- No response record has a password or hash field.
- `RegisterRequest` and `LoginRequest` override `toString()` to mask the password.
- SQL logging is off.

### JWT

- **Signed with HS256**, using a 256-bit key read from config (`app.jwt.secret`), never from source.
- **Claims:** `sub` (user id), `role`, `iss`, `iat`, `exp`. The payload is only Base64URL-encoded, not encrypted, so there is no email, name, password, or hash in it.
- **Validation happens in Spring Security's bearer-token filter** (OAuth2 Resource Server) before any controller runs. It checks the signature first, then `exp`/`nbf` and `iss`. I also added a validator that *requires* `exp`, because the default timestamp validator accepts a token that has no `exp` at all.
- `/api/users/me` identifies the caller only from the token's `sub` claim, so nobody can request another user's profile.

### Token lifetime: 30 minutes

This service issues no refresh token yet, so the access token is the only credential a client holds. If one is stolen it stays valid until it expires and can't be revoked, so it should be short. 30 minutes limits that window while still covering a normal browsing session without constant re-logins. When refresh tokens are added (planned in my Module 3 design), the access token could drop to 10–15 minutes.

### `spring.jpa.hibernate.ddl-auto=none`

I create the table myself with `db/schema.sql`, so the committed SQL is the single source of truth for column types and unique constraints. With `none`, Hibernate never creates, alters, or drops anything, so my test data survives restarts. This also matches the database account having no `CREATE`/`ALTER` permission. (`update` was allowed, but it can quietly drift the schema away from what's committed, and `create-drop` would wipe my test data.)

### Error handling

- **400:** invalid register input (field errors listed) or malformed JSON.
- **409:** duplicate email or username, driven by the database constraint.
- **401 on login:** always `"Invalid email or password"`. For an unknown email the service still runs a bcrypt comparison against a dummy hash, so response *time* doesn't reveal which emails are registered either.
- **401 on `/me`:** missing, tampered, or expired token (from the security filter).

### CORS

Only `app.cors.allowed-origin` (`http://localhost:5173`, my planned front-end dev server) is allowed, on `/api/**`, with `Authorization` and `Content-Type` headers. Not `*`.

### CSRF

CSRF is disabled because the API is stateless and the token travels in the `Authorization` header, not in a cookie. The browser can't attach it automatically to a forged cross-site request.

## Test evidence

Postman collection: `postman/GameFlix-User-Service.postman_collection.json`. Run the requests in order (or use the Collection Runner). Each request has automated checks on the status code and body.

| # | Case | Expected | Result |
|---|---|---|---|
| 1 | Register: success | 201 | Passed (see Word doc) |
| 2 | Register: duplicate email | 409 | Passed (see Word doc) |
| 3 | Register: invalid input | 400 | Passed (see Word doc) |
| 4 | Login: success | 200 + token | Passed (see Word doc) |
| 5 | Login: wrong password | 401 | Passed (see Word doc) |
| 6 | Login: unknown email (same message as #5) | 401 | Passed (see Word doc) |
| 7 | `/me` with valid token | 200 | Passed (see Word doc) |
| 8 | `/me` with no token | 401 | Passed (see Word doc) |
| 9 | `/me` with tampered token (signature changed) | 401 | Passed (see Word doc) |

**Database proof:** `SELECT user_id, email, password_hash FROM users;` shows a stored value beginning with `{bcrypt}$2a$12$`, not the password. (See Word doc.)

## Module 3 design-change note

My Module 3 ERD put every table in one shared database with foreign keys between services (for example `REVIEWS.user_id → USERS`). That contradicted my own microservices architecture, where each service owns its data. The design is now **database-per-service**:

- **User Service:** `gameflix_users` (`users`; later `refresh_tokens`, `password_reset_tokens`)
- **Game Catalog Service:** `gameflix_catalog` (`games`, `platforms`, `genres`, `game_platforms`, `game_genres`)
- **Review Service:** `gameflix_reviews` (`reviews`, `review_helpful_votes`)

Cross-service references such as `reviews.user_id` and `reviews.game_id` are now plain ID columns with no foreign key. The Review Service gets usernames from the User Service API instead of joining. This assignment builds only the User Service, in its own `gameflix_users` database.

I also added attributes my own API contracts already required but the ERD didn't store. For `users`: `password_hash` (VARCHAR(255)), `first_name`, `last_name`, `avatar_url`, `bio`, `role`, `enabled`, `last_login_at`, and `updated_at`, plus a unique constraint on `username`. Finally, the assignment's routes (`/api/auth/register`, `/api/auth/login`, `/api/users/me`) replace my original `/users/register`, `/users/login`, and `/users/:userId`. `/me` is safer than `/users/:userId` because the user comes from the signed token, so changing an ID in the URL can't expose someone else's profile.

## Use of AI assistance

I used Claude (Anthropic) throughout this assignment:

- **Code and project files:** Claude generated the Spring Boot source code (entity, repository, controllers, `AuthService`, `TokenService`, `SecurityConfig`, exception handler), the `schema.sql` and `setup.sql` scripts, the Postman collection, and a first draft of this README.
- **Design:** Claude helped me revise my Module 3 ERD into a database-per-service design and add the missing `users` attributes.
- **Setup and troubleshooting:** I used Claude to walk me through the steps I got stuck on, including running the SQL scripts in MySQL Workbench (and getting my query tab connected), setting up the local properties file and JWT secret, adding the Maven wrapper, and running the Postman tests.

I ran the service against my own MySQL database, executed all nine Postman tests, and checked the results and the stored bcrypt hash myself.

**Something I changed or rejected, and why:** The generated `db/setup.sql` used a placeholder database password. To run it, I replaced the placeholder with my real local password, then changed it back to the placeholder before committing. Committing the real credentials would have exposed my database and broken the assignment's rule against committed secrets, so the real password lives only in my git-ignored `application-local.properties`.
