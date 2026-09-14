CREATE TYPE otp_purpose AS ENUM (
    'EMAIL_VERIFICATION',
    'PASSWORD_RESET',
    'LOGIN_2FA'
);

CREATE TYPE otp_status AS ENUM (
    'PENDING',
    'VERIFIED',
    'EXPIRED'
);

CREATE TABLE otp
(
    id         UUID        NOT NULL PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users (id),
    code       VARCHAR     NOT NULL,
    purpose    otp_purpose NOT NULL,
    attempts   INT         NOT NULL,
    status     otp_status  NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ
);

CREATE INDEX idx_otp_user_id ON otp (user_id);
