-- 人工修改风险结果：留痕列
--
-- 设计：**原地改写** bank_risk_results 同一行的 indicators / risk_level，
-- 模型原始输出快照存进 edit_state（NULL = 纯模型结果）。
-- 之所以不另建 override 表：出图 SQL（indicators->>'result'）、报告 CSV、AI 工具
-- 都直接读这张表，原地改写让它们零改造自动跟随；另建表则要在 5+ 条读路径做 merge，
-- 漏一处就会出现「地图改了、报告没改」。
--
-- edit_state 结构：
-- {
--   "original_indicators": { ...模型原始 indicators 全量快照... },
--   "original_risk_level": 1,
--   "edited_at": "2026-10-19T09:12:33+08:00",
--   "edited_by": 4,
--   "note": "该断面 2024 年已实施护岸加固"
-- }
--
-- ⚠️ 本仓库的 migration/V*.sql 只是留档，不在 Flyway 内，需要手动对目标库执行。

ALTER TABLE bank_risk_results
  ADD COLUMN IF NOT EXISTS edit_state jsonb;

COMMENT ON COLUMN bank_risk_results.edit_state IS
  '人工修改留痕；NULL = 纯模型结果。含 original_indicators/original_risk_level/edited_at/edited_by/note';
