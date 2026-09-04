-- 观测落库三表（TracePersistence 依赖；schema-full.sql 缺失，单独补建）
CREATE TABLE IF NOT EXISTS task_history (
    session_id      VARCHAR(64) PRIMARY KEY,
    user_id         BIGINT       NOT NULL DEFAULT -1,
    status          VARCHAR(16)  NOT NULL DEFAULT 'RUNNING',
    current_step    VARCHAR(64)  NOT NULL DEFAULT '',
    submit_ms       BIGINT       NOT NULL,
    last_active_ms  BIGINT       NOT NULL,
    model_calls_used INT         NOT NULL DEFAULT -1,
    prompt          TEXT,
    INDEX idx_submit (submit_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS trace_span (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    agent     VARCHAR(32)  NOT NULL DEFAULT '',
    layer     VARCHAR(16)  NOT NULL DEFAULT '',
    name      VARCHAR(128) NOT NULL DEFAULT '',
    detail    TEXT,
    success   TINYINT      NOT NULL DEFAULT 0,
    cost_ms   BIGINT       NOT NULL DEFAULT -1,
    ts        BIGINT       NOT NULL,
    attempts  TEXT,
    INDEX idx_session_ts (session_id, ts)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS llm_cost_daily (
    day           DATE         NOT NULL,
    dimension     VARCHAR(32)  NOT NULL,
    calls         BIGINT       NOT NULL DEFAULT 0,
    input_tokens  BIGINT       NOT NULL DEFAULT 0,
    output_tokens BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (day, dimension)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
