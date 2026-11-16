# GameFlix User Service

Registration, login, and JWT-protected profile for GameFlix. Built in Module 4, containerized and given a CI/CD pipeline with supply-chain checks in Module 5, and unit tested with JUnit 5.

A Spring Boot service that registers users, hashes their passwords with bcrypt, issues a signed JWT on login, and uses that token to protect `GET /api/users/me`. Users are stored in MySQL.

**Stack:** Java 21 (compile target; also tested running on JDK 24), Spring Boot 3.5, Spring Security 6 (OAuth2 Resource Server for JWT), Spring Data JPA, MySQL 8, JUnit 5 + Mockito, Docker Compose, GitHub Actions.

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

## Unit tests (JUnit 5)

The project uses **JUnit 5** (Spring Boot 3.x) through `spring-boot-starter-test`, which also brings in Mockito.

**Test class:** [`src/test/java/com/gameflix/userservice/auth/AuthServiceTest.java`](src/test/java/com/gameflix/userservice/auth/AuthServiceTest.java)

These are unit tests for `AuthService`, the service that holds the registration and login logic. `UserRepository` and `TokenService` are mocked with Mockito, so the tests don't need MySQL or a JWT secret and run anywhere, including in the GitHub Actions pipeline. The password encoder is a real `BCryptPasswordEncoder` (cost 4 to keep the tests fast), so hashing is actually exercised instead of faked.

| Test | What it checks |
|---|---|
| `register_ShouldHashPasswordAndNormalizeEmail` | Email is trimmed and lowercased, username trimmed, and the stored value is a bcrypt hash that matches the password, never the plain text |
| `register_DuplicateUsername_ShouldThrowDuplicateAccountException` | A `uk_users_username` constraint violation becomes `"Username is already taken"` (409) |
| `register_PasswordOver72Bytes_ShouldBeRejected` | Passwords over bcrypt's 72-byte limit are rejected and nothing is saved |
| `login_WrongPassword_ShouldThrowGenericError` | Wrong password gives the generic `"Invalid email or password"` and no token is issued |
| `login_UnknownEmail_ShouldThrowSameGenericError` | An unknown email gets the exact same message, so emails can't be enumerated |
| `login_ValidCredentials_ShouldReturnBearerToken` | Correct login returns a Bearer token with the right lifetime and records `lastLoginAt` |

**Run them:**
```
.\mvnw.cmd test        (Windows)
./mvnw test            (macOS/Linux)
```
Result: `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0` and BUILD SUCCESS.

I used Mockito unit tests instead of `@SpringBootTest` on purpose. `@SpringBootTest` starts the whole application, which needs a running MySQL database and the JWT secret just to load. That would make the tests fail in CI and on any machine without the local setup, even when the service logic is correct.

## Test evidence (Postman)

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

## Run with Docker
Runs the service and a MySQL 8.0 database in separate containers with Docker Compose.

**Prerequisites:** Docker Desktop (engine running), JDK 21.

**Backup:** the working pre-Docker version of this project is preserved in Git at commit `d4713a8`.

1. Copy `.env.example` to `.env` and fill in classroom-only values, including a Base64 JWT secret (32+ bytes). `.env` is git-ignored.
2. Build the jar (this also runs the unit tests; add `-DskipTests` to skip them):
   ```
   .\mvnw.cmd clean package     (Windows)
   ./mvnw clean package         (macOS/Linux)
   ```
3. Start both containers:
   ```
   docker compose up --build -d
   docker compose ps
   ```
   MySQL should be `healthy` and the app `Up`. Compose waits for MySQL's health check before starting the app.
4. Test with the Postman collection against `http://localhost:8080` (register → login → `/api/users/me`). All 17 tests pass against the Dockerized app.
5. Check the data:
   ```
   docker compose exec mysql mysql -u<MYSQL_USER> -p <MYSQL_DATABASE>
   SELECT user_id, email, username FROM users;
   ```
6. Stop: `docker compose down` (keeps data). `docker compose down -v` deletes the database volume.

**Notes**
- Inside Docker the app reaches MySQL at `mysql:3306` (the Compose service name); from the host it's `127.0.0.1:3307`.
- `ddl-auto` stays `none` instead of `update`. Instead, `db/schema.sql` is mounted into `/docker-entrypoint-initdb.d/`, so MySQL creates the `users` table on first startup.
- The app runs with the `docker` profile, so it ignores `application-local.properties` and gets its DB credentials and JWT secret from environment variables set in `compose.yaml`.
- The Dockerfile uses `eclipse-temurin:21-jre` to match the project's Java 21 target.
- After changing Java code, rebuild the jar before `docker compose up --build`, since the Dockerfile copies the jar from `target/`.

## CI/CD pipeline and supply-chain security

`.github/workflows/ci.yml` runs on every push and pull request to `main`:

1. Check out the code and set up JDK 21
2. **Build with Maven** (`mvn clean package`), which compiles the code and runs the JUnit tests
3. **Build the Docker image**
4. **Generate an SBOM** with Syft (CycloneDX JSON) from the built image
5. **Upload the SBOM** as a build artifact, so every build has a record of exactly what it shipped
6. **Vulnerability report:** Trivy lists all HIGH and CRITICAL findings (never fails the build)
7. **Security gate:** Trivy fails the build on any CRITICAL finding

The SBOM and scan steps run after the image is built, because the image is what actually ships, including base-image packages that a source-only scan would miss. They run before anything would be pushed or deployed, so a vulnerable image can't go anywhere.

**Current status:** the security gate fails the build on purpose. The image has 10 CRITICAL findings, all in libraries pulled in transitively by Spring Boot 3.5.6 (embedded Tomcat, `spring-webmvc`, `spring-security-web`). Upgrading Spring Boot and rescanning is the next step to get the pipeline green.

## Use of AI assistance

I used Claude (Anthropic) throughout this assignment:

- **Code and project files:** Claude generated the Spring Boot source code (entity, repository, controllers, `AuthService`, `TokenService`, `SecurityConfig`, exception handler), the `schema.sql` and `setup.sql` scripts, the Postman collection, and a first draft of this README.
- **Module 5 (Docker, CI/CD, testing):** Claude helped me write the Dockerfile and `compose.yaml`, add the SBOM and Trivy steps to the pipeline, and write `AuthServiceTest`. The assignment's example tested a `MovieService`, which this service doesn't have, so the tests were written against my actual `AuthService` logic instead. I ran `mvn test` myself and confirmed all 6 tests pass.
- **Design:** Claude helped me revise my Module 3 ERD into a database-per-service design and add the missing `users` attributes.
- **Setup and troubleshooting:** I used Claude to walk me through the steps I got stuck on, including running the SQL scripts in MySQL Workbench (and getting my query tab connected), setting up the local properties file and JWT secret, adding the Maven wrapper, and running the Postman tests.

I ran the service against my own MySQL database, executed all nine Postman tests, and checked the results and the stored bcrypt hash myself.

**Something I changed or rejected, and why:** The generated `db/setup.sql` used a placeholder database password. To run it, I replaced the placeholder with my real local password, then changed it back to the placeholder before committing. Committing the real credentials would have exposed my database and broken the assignment's rule against committed secrets, so the real password lives only in my git-ignored `application-local.properties`.
