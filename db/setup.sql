-- ---------------------------------------------------------------------------
-- Run ONCE as root in MySQL Workbench. Creates the User Service's own database
-- and a least-privilege app account (it can read/write rows, but cannot
-- create, alter, or drop tables).
--
-- Replace CHANGE_ME_DB_PASSWORD with your own local password, and put the same
-- value in src/main/resources/application-local.properties. Do not commit it.
-- ---------------------------------------------------------------------------

CREATE DATABASE IF NOT EXISTS gameflix_users
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

CREATE USER 'gameflix_app'@'localhost' IDENTIFIED BY 'Change_me_DB_PassWord';
GRANT SELECT, INSERT, UPDATE, DELETE ON gameflix_users.* TO 'gameflix_app'@'localhost';
FLUSH PRIVILEGES;
