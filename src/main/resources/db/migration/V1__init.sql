-- ══════════════════════════════════════════════════════════════════
-- EMVAgent Backend — Database Schema
-- Flyway Migration V1
--
-- Tables:
--   1. users           → Internal users (bank/acquirer/processor teams)
--   2. chat_sessions   → Conversation threads
--   3. chat_messages   → Individual messages (user + AI)
--   4. feedback        → User feedback on AI responses (human review queue)
-- ══════════════════════════════════════════════════════════════════

-- ── 1. USERS ────────────────────────────────────────────────────
CREATE TABLE users (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username            VARCHAR(100) NOT NULL UNIQUE,
    password            VARCHAR(255) NOT NULL,       -- BCrypt hashed
    email               VARCHAR(255) NOT NULL UNIQUE,
    organization_type   VARCHAR(50),                 -- BANK, ACQUIRER, PROCESSOR, POS_GATEWAY, NATIONAL_SWITCH
    organization_name   VARCHAR(255),                -- e.g. "Chase Bank", "Worldpay"
    role                VARCHAR(50) NOT NULL DEFAULT 'USER',  -- USER, EMV_EXPERT, ADMIN
    enabled             BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Seed: default admin user (password: changeme123 — BCrypt)
INSERT INTO users (username, password, email, organization_type, organization_name, role)
VALUES (
    'admin@emvagent.com',
    '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQyCoBGG4K6u6AzV6jdRIqF2.',
    'admin@emvagent.com',
    'FAIRBIT_INTERNAL',
    'Fairbit',
    'ADMIN'
);

-- Seed: EMV Expert user (password: emvexpert123 — BCrypt)
INSERT INTO users (username, password, email, organization_type, organization_name, role)
VALUES (
    'emv.expert@emvagent.com',
    '$2a$12$XmPfT9VnXKz7JqwNkHqDLuCbGWQJIz0F8MSxEoGpjHGKCa1hfY5E2',
    'emv.expert@emvagent.com',
    'FAIRBIT_INTERNAL',
    'Fairbit',
    'EMV_EXPERT'
);

-- ── 2. CHAT SESSIONS ─────────────────────────────────────────────
CREATE TABLE chat_sessions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username            VARCHAR(100) NOT NULL REFERENCES users(username),
    organization_type   VARCHAR(50),
    started_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_activity_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    message_count       INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_chat_sessions_username     ON chat_sessions(username);
CREATE INDEX idx_chat_sessions_last_activity ON chat_sessions(last_activity_at DESC);

-- ── 3. CHAT MESSAGES ─────────────────────────────────────────────
CREATE TABLE chat_messages (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id           UUID NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    role                 VARCHAR(20) NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    content              TEXT NOT NULL,
    agent_used           VARCHAR(50),               -- CAPTAIN, SPEC, DATA, DIAGNOSIS
    confidence_score     DOUBLE PRECISION,
    root_cause_detected  VARCHAR(200),              -- EMV root cause label if detected
    emv_tags             JSONB,                     -- raw EMV tags from the message
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_chat_messages_session_id  ON chat_messages(session_id);
CREATE INDEX idx_chat_messages_created_at  ON chat_messages(session_id, created_at ASC);
CREATE INDEX idx_chat_messages_root_cause  ON chat_messages(root_cause_detected)
    WHERE root_cause_detected IS NOT NULL;

-- ── 4. FEEDBACK ──────────────────────────────────────────────────
CREATE TABLE feedback (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id           UUID NOT NULL REFERENCES chat_messages(id),
    session_id           UUID NOT NULL REFERENCES chat_sessions(id),
    username             VARCHAR(100) NOT NULL REFERENCES users(username),

    -- User input
    rating               VARCHAR(20) NOT NULL CHECK (rating IN ('THUMBS_UP', 'THUMBS_DOWN')),
    corrected_answer     TEXT,
    corrected_label      VARCHAR(200),              -- user's suggested EMV root cause label
    notes                TEXT,
    emv_tags             JSONB,

    -- Review workflow
    status               VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                             CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    reviewed_by          VARCHAR(100),
    reviewed_at          TIMESTAMPTZ,
    approved_label       VARCHAR(200),              -- EMV expert confirmed label
    reviewer_notes       TEXT,

    -- BigQuery export tracking
    exported_to_bigquery BOOLEAN NOT NULL DEFAULT FALSE,

    submitted_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_feedback_status         ON feedback(status);
CREATE INDEX idx_feedback_submitted_at   ON feedback(submitted_at ASC);
CREATE INDEX idx_feedback_not_exported   ON feedback(status, exported_to_bigquery)
    WHERE status = 'APPROVED' AND exported_to_bigquery = FALSE;
CREATE INDEX idx_feedback_approved_label ON feedback(approved_label)
    WHERE approved_label IS NOT NULL;
