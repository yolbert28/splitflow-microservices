CREATE TYPE outbox_status AS ENUM (
    'PENDING',
    'PUBLISHED',
    'FAILED'
);

CREATE TABLE outbox
(
    id             UUID           NOT NULL PRIMARY KEY,
    aggregate_id   UUID,
    aggregate_type VARCHAR        NOT NULL,
    event_type     VARCHAR        NOT NULL,
    payload        JSONB,
    status         outbox_status  NOT NULL,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    published_at   TIMESTAMPTZ
);
