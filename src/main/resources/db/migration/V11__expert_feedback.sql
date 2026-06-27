-- ══════════════════════════════════════════════════════════════════
-- EMVAgent — Pro "Feedback to the expert" widget
-- Flyway Migration V11
--
-- Separate, additive table for the floating Pro feedback widget (1-5 score +
-- free-text "feedback for the expert"). Intentionally independent from the
-- existing `feedback` table (per-message thumbs up/down) so none of the existing
-- review-queue / BigQuery-export flows are affected. Context columns are nullable
-- because the widget is always available, even when no answer is on screen.
-- ══════════════════════════════════════════════════════════════════

CREATE TABLE expert_feedback (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username       VARCHAR(100) NOT NULL,
    score          SMALLINT     NOT NULL CHECK (score BETWEEN 1 AND 5),
    message        TEXT         NOT NULL,

    -- Optional context captured at submit time (null when no active answer)
    session_id     UUID,
    message_id     UUID,
    user_question  TEXT,
    ai_answer      TEXT,
    emv_tags       TEXT,

    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_expert_feedback_created_at ON expert_feedback(created_at DESC);
