CREATE TABLE session
(
    id                  UUID        NOT NULL PRIMARY KEY,
    user_id             UUID        NOT NULL REFERENCES users (id),
    refresh_token_hash  VARCHAR     NOT NULL,
    ip_address          INET        NOT NULL,
    device_info         VARCHAR,
    revoked             BOOLEAN     NOT NULL,
    revoked_at          TIMESTAMPTZ,
    last_used_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at          TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_session_refresh_token_hash ON session (refresh_token_hash);
CREATE INDEX idx_session_user_id ON session (user_id);
