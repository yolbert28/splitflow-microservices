CREATE TABLE users
(
    id            UUID        NOT NULL PRIMARY KEY,
    full_name     VARCHAR     NOT NULL,
    email         VARCHAR     NOT NULL UNIQUE,
    password_hash TEXT        NOT NULL,
    friend_code   TEXT        NOT NULL UNIQUE,
    created_at    TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP   NOT NULL DEFAULT NOW(),
    verified_at   TIMESTAMP,
    deleted_at    TIMESTAMP
);
