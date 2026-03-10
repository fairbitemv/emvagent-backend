CREATE TABLE password_reset_tokens (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    token         VARCHAR(255) NOT NULL UNIQUE,
    username      VARCHAR(255) NOT NULL,
    expires_at    TIMESTAMPTZ  NOT NULL,
    used          BOOLEAN      NOT NULL DEFAULT FALSE
);

ALTER TABLE users
  ADD COLUMN IF NOT EXISTS cancel_at_period_end BOOLEAN NOT NULL DEFAULT FALSE;
