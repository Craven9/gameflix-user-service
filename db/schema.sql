-- ---------------------------------------------------------------------------
-- GameFlix User Service schema (database: gameflix_users)
-- This service owns this table. No other service reads it directly; they call
-- the User Service API instead (database-per-service).
-- ---------------------------------------------------------------------------

USE gameflix_users;

CREATE TABLE IF NOT EXISTS users (
  user_id        BIGINT        NOT NULL AUTO_INCREMENT,
  email          VARCHAR(255)  NOT NULL,
  username       VARCHAR(50)   NOT NULL,
  password_hash  VARCHAR(255)  NOT NULL,  -- sized for the hash: {bcrypt} hash = 68 chars
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
  CONSTRAINT uk_users_email    UNIQUE (email),     -- 409 on duplicate email comes from HERE
  CONSTRAINT uk_users_username UNIQUE (username)
);
