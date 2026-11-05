-- 全局搜索：中文子串检索所需的 trigram 索引
--
-- 为什么不用 tsvector：PG 内置分词器把整句中文当成一个 token
--   to_tsvector('simple','长江下游张家港岸段冲刷严重') -> '长江下游张家港岸段冲刷严重':1
-- 装 zhparser / pg_jieba 在 Windows 下需 MSVC 编译 SCWS，目标机没有工具链。
-- pg_trgm 随 contrib 自带，且能就地支持 LIKE '%中文%'（实测 0.14ms / 23841 行）。
--
-- 注意：中文 2 字查询会让 GIN trigram 退化为全索引扫描（实测 9ms，略慢于 seq scan）。
-- 查询层对短词做了限制（见 SearchQueryParser），不要指望这里能救它。

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 任务：名称 + 描述
CREATE INDEX IF NOT EXISTS idx_tasks_name_trgm
    ON tasks USING gin (task_name gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_tasks_desc_trgm
    ON tasks USING gin (description gin_trgm_ops);

-- 岸段：名称
CREATE INDEX IF NOT EXISTS idx_banks_name_trgm
    ON banks USING gin (bank_name gin_trgm_ops);

-- 断面：名称 + 参数字段名（param_name 是用户可读的参数集名，如「示例1」）
CREATE INDEX IF NOT EXISTS idx_sections_name_trgm
    ON cross_sections USING gin (section_name gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_sections_param_trgm
    ON cross_sections USING gin (param_name gin_trgm_ops);

-- 断面剖面：DEM 路径（tiff/YZ/2018/standard/201804/2018-YZ.tif）
CREATE INDEX IF NOT EXISTS idx_profiles_dem_trgm
    ON section_profiles USING gin (dem_id gin_trgm_ops);

-- 报告文件名（report_task-xxx_20261015_161434.md）
CREATE INDEX IF NOT EXISTS idx_reports_name_trgm
    ON ai_report_runs USING gin (report_file_name gin_trgm_ops);
