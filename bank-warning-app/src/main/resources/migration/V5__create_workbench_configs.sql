CREATE TABLE IF NOT EXISTS ai_workbench_configs (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    config_json     TEXT NOT NULL,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_awc_user_id ON ai_workbench_configs(user_id);
