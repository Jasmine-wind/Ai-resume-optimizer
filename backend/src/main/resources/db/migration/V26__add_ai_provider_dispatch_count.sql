ALTER TABLE ai_usage_records
    ADD COLUMN gateway_attempt_count INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN provider_dispatch_count INTEGER NOT NULL DEFAULT 1;

ALTER TABLE ai_usage_records
    ADD CONSTRAINT ck_ai_usage_records_gateway_attempt_count_positive
    CHECK (gateway_attempt_count > 0),
    ADD CONSTRAINT ck_ai_usage_records_dispatch_count_positive
    CHECK (provider_dispatch_count > 0);
