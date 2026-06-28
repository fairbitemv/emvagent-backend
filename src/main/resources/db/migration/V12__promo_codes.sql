-- ══════════════════════════════════════════════════════════════════
-- EMVAgent — Promo codes (time-limited free access via shared invite link)
-- Flyway Migration V12
--
-- Self-serve "N months free Professional" invite links. A single shared code
-- (capped by max_uses) grants ACTIVE for duration_days. Independent from Stripe:
-- expiry is tracked on users.promo_expires_at and enforced at access time, so no
-- Stripe coupon/checkout is involved and paying customers are unaffected.
-- ══════════════════════════════════════════════════════════════════

CREATE TABLE promo_codes (
    code          VARCHAR(64)  PRIMARY KEY,
    plan_status   VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',   -- status granted on redeem
    duration_days INT          NOT NULL,                    -- access length (e.g. 180)
    max_uses      INT          NOT NULL,                    -- total redemptions allowed
    used_count    INT          NOT NULL DEFAULT 0,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE promo_redemptions (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    code        VARCHAR(64)  NOT NULL REFERENCES promo_codes(code),
    username    VARCHAR(100) NOT NULL,
    redeemed_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_promo_redemption UNIQUE (code, username)   -- one redemption per user per code
);

CREATE INDEX idx_promo_redemptions_code ON promo_redemptions(code);

-- Promo-grant expiry on the user (nullable, additive). Never touched by Stripe.
ALTER TABLE users ADD COLUMN IF NOT EXISTS promo_expires_at TIMESTAMPTZ;

-- Seed: 6-month free Professional invite (180 days, up to 15 redemptions).
INSERT INTO promo_codes (code, plan_status, duration_days, max_uses)
VALUES ('6MONTHPROFREE', 'ACTIVE', 180, 15);
