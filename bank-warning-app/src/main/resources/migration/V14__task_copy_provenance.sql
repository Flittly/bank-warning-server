-- 任务复制：来源留痕
--
-- 设计：复制出来的任务不是「原件」，但界面上两者长得一模一样 —— 尤其是
-- `include_results=true` 时，副本里挂着别人（或别的参数）算出来的结论。
-- 因此把来源落到列上，而不是塞进 description（用户改名/清描述就抹掉了）。
--
-- ① tasks.source_task_id / copied_at —— 这个副本从哪来、什么时候复制
-- ② bank_risk_results.source_task_id —— 这条结论是从哪个任务搬来的（NULL = 本任务自己算的）
--
-- 刻意不建外键：源任务被删（软删）后副本要照常可用，留一个自由文本 id 足够追溯。
--
-- ⚠️ 本仓库的 migration/V*.sql 只是留档，不在 Flyway 内，需要手动对目标库执行：
--    psql -h localhost -U postgres -d bank_risk_db -f V14__task_copy_provenance.sql

ALTER TABLE tasks ADD COLUMN IF NOT EXISTS source_task_id varchar(100);
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS copied_at timestamp;

COMMENT ON COLUMN tasks.source_task_id IS
    '复制来源任务 id；NULL = 用户直接创建（非复制）';
COMMENT ON COLUMN tasks.copied_at IS
    '复制发生时刻；与 created_at 通常相同，单独留一列以区分「建任务」与「复制任务」';

-- 部分索引：绝大多数行 source_task_id 为 NULL，全表索引没有意义
CREATE INDEX IF NOT EXISTS idx_tasks_source_task_id
    ON tasks(source_task_id) WHERE source_task_id IS NOT NULL;

ALTER TABLE bank_risk_results ADD COLUMN IF NOT EXISTS source_task_id varchar(100);

COMMENT ON COLUMN bank_risk_results.source_task_id IS
    '结果复制来源任务 id；NULL = 本任务自己算出来的（含人工改写，改写留痕仍看 edit_state）';

CREATE INDEX IF NOT EXISTS idx_bank_risk_results_source_task
    ON bank_risk_results(source_task_id) WHERE source_task_id IS NOT NULL;
