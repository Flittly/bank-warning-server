-- V11: 参数模板按任务隔离
--
-- 背景：basic_params 原本只有「模板库」一种作用域，断面通过 basic_param_id 引用它。
-- 于是编辑器里的「参数模板」面板实际写的是全库共享的那一份，一次保存就能改到所有
-- 引用它的任务（2026-10-17 实测：模板 166 下 13822 条断面横跨 15 个任务被一次改写）。
--
-- 语义改为：
--   task_id IS NULL      → 模板库（主数据，可复用；修改它永远不影响任何已有任务）
--   task_id = 'task-xxx' → 该任务专属参数集（每个任务一份，独立编辑、独立保存）
--
-- 任务参数由首次访问时物化（见 BusinessStoreService#getTaskParams）：优先取该任务
-- 断面的现有快照，其次取显式指定/默认模板，因此本次迁移不需要回填任何历史数据，
-- 也不会改写任何已存在任务的输入。

ALTER TABLE public.basic_params
    ADD COLUMN IF NOT EXISTS task_id character varying(100);

COMMENT ON COLUMN public.basic_params.task_id IS
    'NULL=模板库；非 NULL=该任务专属参数集。任务参数只允许影响本任务。';

CREATE INDEX IF NOT EXISTS idx_basic_params_task_id
    ON public.basic_params (task_id)
    WHERE task_id IS NOT NULL;

-- 一个任务只允许一份参数集（软删的行仍占位，物化时会复活而不是重复插入）
CREATE UNIQUE INDEX IF NOT EXISTS uq_basic_params_task_id
    ON public.basic_params (task_id)
    WHERE task_id IS NOT NULL AND deleted_at IS NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_basic_params_task'
    ) THEN
        ALTER TABLE public.basic_params
            ADD CONSTRAINT fk_basic_params_task
            FOREIGN KEY (task_id) REFERENCES public.tasks (task_id) ON DELETE CASCADE;
    END IF;
END
$$;
