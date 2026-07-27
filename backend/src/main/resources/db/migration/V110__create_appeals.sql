CREATE TABLE appeals (
    appeal_id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL,
    related_lock_reason TEXT,
    appeal_text TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    reviewed_by INTEGER,
    reviewed_at TIMESTAMP,
    admin_note TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_appeals_user
        FOREIGN KEY (user_id) REFERENCES users(user_id),
    CONSTRAINT fk_appeals_reviewed_by
        FOREIGN KEY (reviewed_by) REFERENCES users(user_id),
    CONSTRAINT chk_appeals_status
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'))
);

CREATE TABLE appeal_evidence_urls (
    appeal_id INTEGER NOT NULL,
    evidence_url TEXT NOT NULL,
    CONSTRAINT fk_appeal_evidence_urls_appeal
        FOREIGN KEY (appeal_id) REFERENCES appeals(appeal_id) ON DELETE CASCADE
);

CREATE INDEX idx_appeals_user_id ON appeals(user_id);
CREATE INDEX idx_appeals_status ON appeals(status);
CREATE INDEX idx_appeals_created_at ON appeals(created_at);
CREATE UNIQUE INDEX ux_appeals_one_pending_per_user
    ON appeals(user_id)
    WHERE status = 'PENDING';

ALTER TABLE notifications DROP CONSTRAINT IF EXISTS notifications_notification_type_check;

ALTER TABLE notifications ADD CONSTRAINT notifications_notification_type_check
    CHECK (notification_type IN (
        'BOOKING', 'PAYMENT', 'PROMOTION', 'SYSTEM', 'REVIEW', 'COMPLAINT',
        'OWNER_APPROVAL', 'STADIUM_APPROVAL', 'ACCOUNT_LOCK', 'APPEAL'
    ));
