ALTER TABLE users
  ADD COLUMN stripe_customer_id      VARCHAR(255),
  ADD COLUMN stripe_subscription_id  VARCHAR(255),
  ADD COLUMN subscription_status     VARCHAR(50)  NOT NULL DEFAULT 'INACTIVE',
  ADD COLUMN subscription_expires_at TIMESTAMPTZ,
  ADD COLUMN daily_chat_count        INT          NOT NULL DEFAULT 0,
  ADD COLUMN daily_reset_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  ADD COLUMN weekly_chat_count       INT          NOT NULL DEFAULT 0,
  ADD COLUMN weekly_reset_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  ADD COLUMN bonus_credits           INT          NOT NULL DEFAULT 0;
