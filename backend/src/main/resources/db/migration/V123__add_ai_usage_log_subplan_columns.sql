ALTER TABLE ai_usage_log ADD COLUMN IF NOT EXISTS suggestion_type VARCHAR(50);
ALTER TABLE ai_usage_log ADD COLUMN IF NOT EXISTS sub_plan_steps INT;
ALTER TABLE ai_usage_log ADD COLUMN IF NOT EXISTS sub_plan_completed INT;
ALTER TABLE ai_usage_log ADD COLUMN IF NOT EXISTS sub_plan_rolled_back BOOLEAN NOT NULL DEFAULT FALSE;
