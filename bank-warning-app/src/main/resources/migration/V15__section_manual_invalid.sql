-- 断面「人工无效」标记（2026-10-26）
--
-- 【为什么不复用 is_valid】
--   is_valid / validation_status / validation_message 是**几何校验**的产物，由
--   SectionValidationService 覆写：重新校验一次就可能把它们改掉。而这里要表达的是
--   **人的判断**（"这条断面位置不对/我不满意，先从图面和分析里摘出去"），只能由用户
--   显式切换，任何校验、计算、复制流程都不许动它。两者合并 = 一次重新校验顺手清掉人工标记。
--
-- 【为什么落在 cross_sections 而不是 bank_risk_results】
--   1. 无效断面常常压根没有结果（还没算 / 算失败），结果表里没有行可以打标；
--   2. 重跑任务第一步就是 clearTaskResults 把该任务的 risk_results 全部软删
--      （section_profiles 同样），标记挂在结果上会随重跑一起消失，等于白标；
--   3. 语义是「这条断面不参与分析」，不是「这次算出来的数不可信」—— 后者才是结果级属性。
--
-- 【为什么 NOT NULL DEFAULT false】
--   读取端（SectionResponse / 前端 SectionResult）只做 `=== true` 判断；允许 NULL 会让
--   「没标过」和「标了再清掉」两种状态在 SQL 里长出三种写法（IS NULL / = false / IS NOT TRUE）。

ALTER TABLE cross_sections
    ADD COLUMN IF NOT EXISTS manual_invalid boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS manual_invalid_reason character varying(200);

COMMENT ON COLUMN cross_sections.manual_invalid IS
    '人工标记为无效断面：不参与着色/统计/出图，图上以灰色虚线显示；与几何校验 is_valid 无关';
COMMENT ON COLUMN cross_sections.manual_invalid_reason IS
    '人工标记无效的原因（选填，≤200 字）';

-- 部分索引：全表绝大多数是 false，普通 btree 没有选择性。
-- 用途是「某任务下有哪些无效断面」这类查询（任务详情、重跑前提示）。
CREATE INDEX IF NOT EXISTS idx_sections_manual_invalid
    ON cross_sections (task_id)
    WHERE manual_invalid;
