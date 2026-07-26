-- user_preference: denormalized profile, refresh 2AM nightly + event-driven
CREATE TABLE user_preference (
    user_id                   INT PRIMARY KEY REFERENCES users(user_id),
    favorite_sport            VARCHAR(50),
    sport_type_id             INT REFERENCES sport_types(sport_type_id),
    preferred_district        VARCHAR(100),
    preferred_province        VARCHAR(100),
    preferred_time_start      TIME,
    preferred_time_end        TIME,
    preferred_weekday         SMALLINT,  -- 1=Mon, 7=Sun
    avg_price_per_booking     DECIMAL(10,2),
    max_price_per_booking     DECIMAL(10,2),
    total_bookings            INT DEFAULT 0,
    total_completed_minutes   INT DEFAULT 0,
    last_booking_date         DATE,
    last_active_date          DATE,
    computed_at               TIMESTAMP DEFAULT NOW(),
    confidence_score          DECIMAL(3,2) DEFAULT 0.0  -- 0.0–1.0
);
CREATE INDEX idx_user_preference_computed ON user_preference(computed_at);

-- notification_log: audit trail for proactive notifications
CREATE TABLE notification_log (
    log_id              BIGSERIAL PRIMARY KEY,
    user_id             INT REFERENCES users(user_id),
    suggestion_type     VARCHAR(50) NOT NULL,  -- PATTERN_MATCH, MATCH_INVITE, BOOKING_REMINDER, LLM_SUGGESTION
    notification_id     BIGINT,
    trigger_reason      TEXT,
    matched_stadium_id  INT,
    matched_match_id    INT,
    was_sent            BOOLEAN DEFAULT FALSE,
    was_clicked        BOOLEAN DEFAULT FALSE,
    was_dismissed       BOOLEAN DEFAULT FALSE,
    generated_by_llm    BOOLEAN DEFAULT FALSE,
    llm_model          VARCHAR(100),
    llm_cost_tokens    INT,
    created_at         TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_notification_log_user ON notification_log(user_id, created_at DESC);

ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'AI_SUGGESTION';
