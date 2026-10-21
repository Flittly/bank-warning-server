package com.yangtze.bankwarning.service;

import com.yangtze.bankwarning.domain.po.*;
import com.yangtze.bankwarning.domain.dto.*;
import com.yangtze.bankwarning.domain.exception.ResultOverrideException;
import com.yangtze.bankwarning.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yangtze.bankwarning.security.security.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class BusinessStoreService {

    private static final Logger log = LoggerFactory.getLogger(BusinessStoreService.class);

    /** 只用于几何比较（纯 JSON 结构比大小），与业务用的 objectMapper 配置无关。 */
    private static final ObjectMapper GEOMETRY_COMPARATOR = new ObjectMapper();

    private final BankMapper bankMapper;
    private final TaskMapper taskMapper;
    private final BasicParamMapper basicParamMapper;
    private final SectionMapper sectionMapper;
    private final RiskResultMapper riskResultMapper;
    private final SectionProfileMapper sectionProfileMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BusinessStoreService(
            BankMapper bankMapper,
            TaskMapper taskMapper,
            BasicParamMapper basicParamMapper,
            SectionMapper sectionMapper,
            RiskResultMapper riskResultMapper,
            SectionProfileMapper sectionProfileMapper) {
        this.bankMapper = bankMapper;
        this.taskMapper = taskMapper;
        this.basicParamMapper = basicParamMapper;
        this.sectionMapper = sectionMapper;
        this.riskResultMapper = riskResultMapper;
        this.sectionProfileMapper = sectionProfileMapper;
    }

    // ==================== Bank ====================

    public BankResponse saveBank(BankPayload payload, boolean overwrite) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        if (overwrite) {
            BankPO existing = bankMapper.selectByBankId(payload.bankId(), userId);
            if (existing != null) {
                BankPO po = toBankPO(payload);
                // 覆盖更新时载荷未携带几何时保留库中已有几何，防止误清空岸段数据
                if (payload.bankGeometry() == null) {
                    po.setBankGeometry(existing.getBankGeometry());
                }
                bankMapper.update(po);
                return BankResponse.from(bankMapper.selectByBankId(payload.bankId(), userId));
            }
        }
        BankPO po = toBankPO(payload);
        po.setUserId(SecurityUtils.getCurrentUserId());
        bankMapper.insert(po);
        return BankResponse.from(bankMapper.selectByBankId(payload.bankId(), userId));
    }

    public List<BankResponse> listBanks(String regionCode) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        return bankMapper.selectAll(regionCode, userId).stream().map(BankResponse::from).collect(Collectors.toList());
    }

    public Map<String, Object> getBank(String bankId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        BankPO po = bankMapper.selectByBankId(bankId, userId);
        if (po == null) {
            throw new IllegalArgumentException("Bank not found: " + bankId);
        }
        return toMap(po);
    }

    public void updateBank(String bankId, BankPayload payload) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        BankPO existing = bankMapper.selectByBankId(bankId, userId);
        if (existing == null) {
            throw new IllegalArgumentException("Bank not found: " + bankId);
        }
        BankPO po = toBankPO(payload);
        po.setBankId(bankId);
        // 载荷未携带几何时保留库中已有几何，防止误清空岸段数据
        if (payload.bankGeometry() == null) {
            po.setBankGeometry(existing.getBankGeometry());
        }
        bankMapper.update(po);
    }

    public void deleteBank(String bankId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        if (bankMapper.deleteByBankId(bankId, userId) == 0) {
            throw new IllegalArgumentException("Bank not found: " + bankId);
        }
    }

    // ==================== Task ====================

    public TaskResponse saveTask(TaskPayload payload, boolean overwrite) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        String taskId = payload.taskId();
        if (overwrite && taskId != null) {
            TaskPO existing = taskMapper.selectByTaskId(taskId, userId);
            if (existing != null) {
                TaskPO po = toTaskPO(payload);
                po.setTaskId(taskId);
                taskMapper.update(po);
                return TaskResponse.from(taskMapper.selectByTaskId(taskId, userId));
            }
        }
        TaskPO po = toTaskPO(payload);
        po.setTaskId(taskId);
        po.setUserId(SecurityUtils.getCurrentUserId());
        taskMapper.insert(po);
        return TaskResponse.from(taskMapper.selectByTaskId(taskId, userId));
    }

    public List<TaskResponse> listTasks() {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        return taskMapper.selectAll(userId).stream().map(TaskResponse::from).collect(Collectors.toList());
    }

    public TaskResponse getTask(String taskId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        TaskPO po = taskMapper.selectByTaskId(taskId, userId);
        if (po == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        return TaskResponse.from(po);
    }

    public void updateTaskStatus(String taskId, String status, String runStartedAt, String runCompletedAt, String errorMessage) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        getTask(taskId);
        taskMapper.updateStatus(taskId, status, runStartedAt, runCompletedAt, errorMessage, userId);
    }

    public void markTaskRunning(String taskId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        taskMapper.markRunning(taskId, userId);
    }

    public void markTaskCompleted(String taskId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        taskMapper.markCompleted(taskId, userId);
    }

    public void markTaskError(String taskId, String errorMessage) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        taskMapper.markError(taskId, errorMessage, userId);
    }

    public void markTaskPartialFailed(String taskId, String errorMessage) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        taskMapper.markPartialFailed(taskId, errorMessage, userId);
    }

    /**
     * 标记任务「已终止」。用于用户主动终止（协作式取消收尾），以及把执行进程已消失的
     * 僵尸 running 补掉 —— 补僵尸这一步必须由调用方做，因为已经没有任何线程会替它写终态了。
     */
    public void markTaskCancelled(String taskId, String errorMessage) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        taskMapper.markCancelled(taskId, errorMessage, userId);
    }

    public void deleteTask(String taskId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        if (taskMapper.deleteByTaskId(taskId, userId) == 0) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        // 任务是软删，FK 的 ON DELETE CASCADE 不会触发 ⇒ 任务参数集要显式一并软删，
        // 否则会留下孤儿行，还会因为 uq_basic_params_task_id 挡住同 id 的重建。
        basicParamMapper.softDeleteByTaskId(taskId, userId);
    }

    /**
     * 把一个任务已有关联的岸段重置为给定集合。
     *
     * <p>为什么需要一条专门的写入口：前端「补充岸段」是「把选中的几条并进已有任务」，
     * 用 {@code POST /tasks} 的 overwrite 语义去做就得先 GET 再整体 POST 回去 ——
     * 既是读改写竞态，又会把 task_name / description 一起覆盖。这里只动 bank_ids 一列。
     *
     * @param mode {@code merge}（默认，取并集）或 {@code replace}（整体替换）
     * @return 合并后的 bank_ids、本次真正新增的、以及库里查不到的
     */
    public Map<String, Object> appendTaskBanks(String taskId, List<String> bankIds, String mode) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        TaskPO existing = taskMapper.selectByTaskId(taskId, userId);
        if (existing == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }

        // 入参先去空、去重；保持用户勾选顺序（LinkedHashSet），不用 HashSet 打乱
        Set<String> incoming = new LinkedHashSet<>();
        if (bankIds != null) {
            for (String id : bankIds) {
                if (id != null && !id.isBlank()) incoming.add(id.trim());
            }
        }

        // 校验岸段存在：不存在的直接报回去，而不是静默写进 bank_ids ——
        // 写进关联却画不出几何，结果就是"任务里多了一条空岸段"，比报错更难排查。
        List<String> missing = new ArrayList<>();
        for (String id : incoming) {
            if (bankMapper.selectByBankId(id, userId) == null) missing.add(id);
        }
        if (!missing.isEmpty()) {
            return Map.of(
                    "success", false,
                    "task_id", taskId,
                    "missing", missing,
                    "message", "以下岸段在库中不存在，未做任何修改：" + String.join(", ", missing));
        }

        boolean replace = "replace".equalsIgnoreCase(String.valueOf(mode).trim());
        BankIdMerge merged = mergeBankIds(readBankIdList(existing.getBankIds()), new ArrayList<>(incoming), replace);

        // 空集合也要写 '[]'：让库里是空数组而不是 keep 旧值/JSON null
        taskMapper.updateBankIds(taskId, writeJson(merged.bankIds()), userId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("task_id", taskId);
        result.put("mode", replace ? "replace" : "merge");
        result.put("added", merged.added());
        result.put("removed", merged.removed());
        result.put("bank_ids", merged.bankIds());
        result.put("total", merged.bankIds().size());
        return result;
    }

    /**
     * 用「该任务断面里实际用到的真实岸段」补全 {@code tasks.bank_ids}（幂等、只增不减）。
     *
     * <p>存在的意义：早年「补充岸段」在写关联之前就失败过（接口不存在 / 请求被中断），
     * 断面落了库、bank_ids 没写。这类任务的症状是：
     * <ul>
     *   <li>资源树（只读 bank_ids）比地图（读并集）少几条 —— 同一份数据两个数字；</li>
     *   <li>「生成计算断面…」走整任务重建，以地图上的岸段为准还好，但任何以
     *       bank_ids 为准的路径都会把那几条岸段静默丢掉。</li>
     * </ul>
     *
     * <p>只写"断面已存在 + 岸段库里有"的 id：自由断面的 {@code line-N} 不算岸段，
     * 写进去只会让前端每次加载都请求一个 404。不动任何断面与结果。
     */
    public Map<String, Object> syncTaskBanksFromSections(String taskId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        TaskPO existing = taskMapper.selectByTaskId(taskId, userId);
        if (existing == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }

        List<String> fromSections = sectionMapper.selectDistinctRealBankIdsByTask(taskId, userId);
        BankIdMerge merged = mergeBankIds(
                readBankIdList(existing.getBankIds()),
                fromSections == null ? new ArrayList<>() : new ArrayList<>(fromSections),
                false);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("task_id", taskId);
        // 只增不减：从断面反推的清单天然是"任务真实用过"的子集，
        // 但 bank_ids 里可能还有"关联了、还没生成断面"的岸段，那些不能在同步时被抹掉。
        if (!merged.added().isEmpty()) {
            taskMapper.updateBankIds(taskId, writeJson(merged.bankIds()), userId);
        }
        result.put("added", merged.added());
        result.put("bank_ids", merged.bankIds());
        result.put("total", merged.bankIds().size());
        return result;
    }

    /**
     * 岸段 id 集合的合并规则（纯函数，便于单测）。
     *
     * <p>三条不变量：
     * <ol>
     *   <li>结果里没有重复、没有空串；</li>
     *   <li>merge = 老的在前、新的按传入顺序追加（老岸段的顺序不该因为补了一条就变）；</li>
     *   <li>replace = 只用传入的，老的里没被保留的进 {@code removed}。</li>
     * </ol>
     */
    record BankIdMerge(List<String> bankIds, List<String> added, List<String> removed) {
    }

    static BankIdMerge mergeBankIds(List<String> existing, List<String> incoming, boolean replace) {
        // 先归一化现存集合：「本次新增」的语义是"不在原集合里"，
        // 与 merge/replace 无关 —— replace 时把原本就在的 b2 也算成 added 会误导前端提示。
        Set<String> existingSet = new LinkedHashSet<>();
        for (String id : existing == null ? List.<String>of() : existing) {
            if (id != null && !id.isBlank()) existingSet.add(id.trim());
        }

        Set<String> next = new LinkedHashSet<>();
        if (!replace) next.addAll(existingSet);

        List<String> added = new ArrayList<>();
        for (String id : incoming == null ? List.<String>of() : incoming) {
            if (id == null || id.isBlank()) continue;
            String v = id.trim();
            if (!existingSet.contains(v) && !added.contains(v)) added.add(v);
            next.add(v);
        }

        List<String> removed = new ArrayList<>();
        if (replace) {
            Set<String> incomingSet = new LinkedHashSet<>();
            for (String id : incoming == null ? List.<String>of() : incoming) {
                if (id != null && !id.isBlank()) incomingSet.add(id.trim());
            }
            for (String id : existingSet) {
                if (!incomingSet.contains(id)) removed.add(id);
            }
        }
        return new BankIdMerge(new ArrayList<>(next), added, removed);
    }


    /** 读 tasks.bank_ids(jsonb) 里的字符串数组；脏数据/非数组一律当空集合，不让它炸掉整个请求。 */
    private List<String> readBankIdList(String json) {
        List<String> list = new ArrayList<>();
        if (json == null || json.isBlank()) return list;
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node != null && node.isArray()) {
                node.forEach(item -> {
                    if (item != null && !item.isNull()) {
                        String v = item.asText("").trim();
                        if (!v.isEmpty()) list.add(v);
                    }
                });
            }
        } catch (Exception e) {
            log.warn("[task-banks] 解析 bank_ids 失败，按空集合处理: {}", e.getMessage());
        }
        return list;
    }

    // ==================== BasicParam ====================

    public BasicParamResponse saveBasicParam(BasicParamPayload payload, boolean overwrite) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        if (overwrite) {
            BasicParamPO existing = basicParamMapper.selectByParamId(payload.paramId(), userId);
            if (existing != null) {
                requireLibraryScope(existing);
                BasicParamPO po = toBasicParamPO(payload);
                basicParamMapper.update(po);
                return BasicParamResponse.from(basicParamMapper.selectByParamId(payload.paramId(), userId));
            }
        }
        BasicParamPO po = toBasicParamPO(payload);
        // 通过这个接口写入的一律是模板库主数据；任务参数只能由任务自己物化/更新，
        // 否则前端一次误带 task_id 就能把一份「私有参数」塞进别人共用的模板列表。
        po.setTaskId(null);
        po.setUserId(SecurityUtils.getCurrentUserId());
        basicParamMapper.insert(po);
        return BasicParamResponse.from(basicParamMapper.selectByParamId(payload.paramId(), userId));
    }

    public List<BasicParamResponse> listBasicParams() {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        return basicParamMapper.selectAll(userId).stream().map(BasicParamResponse::from).collect(Collectors.toList());
    }

    public BasicParamResponse getBasicParam(String paramId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        BasicParamPO po = basicParamMapper.selectByParamId(paramId, userId);
        if (po == null) {
            throw new IllegalArgumentException("Basic parameter not found: " + paramId);
        }
        return BasicParamResponse.from(po);
    }

    public void updateBasicParam(String paramId, BasicParamPayload payload) {
        BasicParamPO existing = getBasicParamPO(paramId);
        // 模板库接口不允许写任务参数行：任务参数只能走 /tasks/{task_id}/params，
        // 否则「改模板」又能顺着 param_id 改到某个任务的参数上，隔离就白做了。
        requireLibraryScope(existing);
        BasicParamPO po = toBasicParamPO(payload);
        po.setParamId(paramId);
        basicParamMapper.update(po);
    }

    private BasicParamPO getBasicParamPO(String paramId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        BasicParamPO po = basicParamMapper.selectByParamId(paramId, userId);
        if (po == null) {
            throw new IllegalArgumentException("Basic parameter not found: " + paramId);
        }
        return po;
    }

    private void requireLibraryScope(BasicParamPO po) {
        if (po.getTaskId() != null) {
            throw new IllegalArgumentException(
                    "该参数集属于任务 " + po.getTaskId() + "，只能通过 /v0/bank/tasks/{task_id}/params 修改");
        }
    }


    // ==================== TaskParams（任务自己的参数集） ====================

    /**
     * 取某个任务自己的参数集；没有就物化一份。
     *
     * 为什么要有这一层：`basic_params` 原来只有模板库一种作用域，编辑器里的「参数模板」
     * 面板写的就是那份全库共享的数据 —— 一次保存能改到所有引用它的任务（实测模板 166
     * 下 13822 条断面横跨 15 个任务被一次改写）。现在把「任务参数」做成独立行
     * （`task_id` 非空），每个任务一份，改谁都只影响自己。
     *
     * 物化取值优先级：
     *   ① 显式指定的模板（templateParamId）—— 新建任务时用户选的那份；
     *   ② 该任务已有断面的第一条快照 —— 历史任务不做回填，直接沿用当年实际跑过的输入；
     *   ③ 模板库里的默认模板 / 任一模板。
     * 物化只读来源、只写「任务参数」这一行，且不会改写任何已有参数值。
     */
    public Map<String, Object> getTaskParams(String taskId, String templateParamId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        getTask(taskId);

        BasicParamPO existing = basicParamMapper.selectByTaskId(taskId, userId);
        if (existing != null) {
            return taskParamsView(taskId, existing, "existing");
        }
        Materialized materialized = materializeTaskParams(taskId, templateParamId, userId);
        return taskParamsView(taskId, materialized.po(), materialized.source());
    }

    /**
     * 只更新「本任务」的参数集，并把本任务的断面快照同步成它。
     *
     * 没有任何按模板 / 按岸段的全量下发分支 —— 这是刻意的：
     * 跨任务批量改写输入会让历史结果不可复现，这类能力不该存在。
     */
    public Map<String, Object> updateTaskParams(String taskId, TaskParamsUpdateRequest request) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        getTask(taskId);

        BasicParamPO po = basicParamMapper.selectByTaskId(taskId, userId);
        if (po == null) {
            po = materializeTaskParams(taskId, null, userId).po();
        }

        BasicParamPO patch = new BasicParamPO();
        patch.setParamId(po.getParamId());
        patch.setParamName(request.paramName());
        patch.setBenchSegment(request.benchSegment());
        patch.setCurrentTimepoint(request.currentTimepoint());
        patch.setSetName(request.setName());
        patch.setWaterQs(request.waterQs());
        patch.setTidalLevel(request.tidalLevel());
        patch.setBenchId(request.benchId());
        patch.setRefId(request.refId());
        patch.setHs(request.hs());
        patch.setHc(request.hc());
        patch.setProtectionLevel(request.protectionLevel());
        patch.setControlLevel(request.controlLevel());
        patch.setComparisonTimepoint(request.comparisonTimepoint());
        patch.setRiskThresholds(writeJson(request.riskThresholds()));
        patch.setWeights(writeJson(request.weights()));
        patch.setOtherParams(writeJson(request.otherParams()));
        basicParamMapper.update(patch);

        int total = sectionMapper.countByTaskId(taskId, userId);
        int updated = sectionMapper.refreshParamsForTask(taskId, userId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("task_id", taskId);
        result.put("param", BasicParamResponse.from(basicParamMapper.selectByTaskId(taskId, userId)));
        result.put("updated_sections", updated);
        result.put("total_sections", total);
        return result;
    }

    /**
     * 显式把模板库里某份模板的内容拷进「本任务」的参数集（再同步本任务断面）。
     * 这是一次性的、用户主动触发的复制 —— 复制后两者再无关联，改模板不会反过来影响它。
     */
    public Map<String, Object> importTaskParamsFromTemplate(String taskId, String templateParamId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        getTask(taskId);

        BasicParamPO template = basicParamMapper.selectByParamId(templateParamId, userId);
        if (template == null) {
            throw new IllegalArgumentException("模板不存在: " + templateParamId);
        }
        if (template.getTaskId() != null) {
            throw new IllegalArgumentException("只能从模板库导入，不能从其它任务的参数集导入: " + templateParamId);
        }

        BasicParamPO po = basicParamMapper.selectByTaskId(taskId, userId);
        if (po == null) {
            po = materializeTaskParams(taskId, templateParamId, userId).po();
        } else {
            BasicParamPO patch = new BasicParamPO();
            patch.setParamId(po.getParamId());
            patch.setParamName(template.getParamName());
            patch.setBenchSegment(template.getBenchSegment());
            patch.setCurrentTimepoint(template.getCurrentTimepoint());
            patch.setSetName(template.getSetName());
            patch.setWaterQs(template.getWaterQs());
            patch.setTidalLevel(template.getTidalLevel());
            patch.setBenchId(template.getBenchId());
            patch.setRefId(template.getRefId());
            patch.setHs(template.getHs());
            patch.setHc(template.getHc());
            patch.setProtectionLevel(template.getProtectionLevel());
            patch.setControlLevel(template.getControlLevel());
            patch.setComparisonTimepoint(template.getComparisonTimepoint());
            patch.setRiskThresholds(template.getRiskThresholds());
            patch.setWeights(template.getWeights());
            patch.setOtherParams(template.getOtherParams());
            basicParamMapper.update(patch);
        }

        int total = sectionMapper.countByTaskId(taskId, userId);
        int updated = sectionMapper.refreshParamsForTask(taskId, userId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("task_id", taskId);
        result.put("template_param_id", templateParamId);
        result.put("param", BasicParamResponse.from(basicParamMapper.selectByTaskId(taskId, userId)));
        result.put("updated_sections", updated);
        result.put("total_sections", total);
        return result;
    }

    private record Materialized(BasicParamPO po, String source) {
    }

    private Map<String, Object> taskParamsView(String taskId, BasicParamPO po, String source) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("task_id", taskId);
        result.put("param", BasicParamResponse.from(po));
        result.put("source", source);
        return result;
    }

    private Materialized materializeTaskParams(String taskId, String templateParamId, Long userId) {
        BasicParamPO source = null;
        String sourceLabel = null;

        if (templateParamId != null && !templateParamId.isBlank()) {
            source = basicParamMapper.selectByParamId(templateParamId, userId);
            if (source == null) {
                throw new IllegalArgumentException("模板不存在: " + templateParamId);
            }
            if (source.getTaskId() != null) {
                throw new IllegalArgumentException("只能从模板库初始化任务参数: " + templateParamId);
            }
            sourceLabel = "template";
        }

        if (source == null) {
            SectionPO first = sectionMapper.selectFirstByTaskId(taskId, userId);
            if (first != null) {
                source = fromSectionSnapshot(first);
                sourceLabel = "section";
            }
        }

        if (source == null) {
            List<BasicParamPO> library = basicParamMapper.selectAll(userId);
            source = library.stream()
                    .filter(p -> isDefaultTemplate(p))
                    .findFirst()
                    .orElse(library.isEmpty() ? null : library.get(0));
            if (source != null) {
                sourceLabel = "default";
            }
        }

        if (source == null) {
            throw new IllegalStateException(
                    "无法初始化任务参数：该任务没有断面，模板库里也没有可用模板。请先创建参数模板。");
        }

        BasicParamPO revived = basicParamMapper.selectByTaskIdIncludingDeleted(taskId, userId);
        if (revived != null) {
            // 曾经物化过、后来被软删（例如任务数据被清过）⇒ 复活并覆盖成本次来源的值
            basicParamMapper.reviveByTaskId(taskId, userId);
            BasicParamPO patch = copyParamValues(source, revived.getParamId());
            basicParamMapper.update(patch);
            BasicParamPO saved = basicParamMapper.selectByTaskId(taskId, userId);
            sectionMapper.assignBasicParamForTask(taskId, saved.getId().intValue(), userId);
            return new Materialized(saved, sourceLabel);
        }

        BasicParamPO po = copyParamValues(source, taskParamIdOf(taskId));
        po.setTaskId(taskId);
        po.setUserId(SecurityUtils.getCurrentUserId());
        basicParamMapper.insert(po);
        BasicParamPO saved = basicParamMapper.selectByTaskId(taskId, userId);
        sectionMapper.assignBasicParamForTask(taskId, saved.getId().intValue(), userId);
        return new Materialized(saved, sourceLabel);
    }

    private String taskParamIdOf(String taskId) {
        // basic_params.param_id 是全库唯一（VARCHAR(100)）；task_id 形如 task-<uuid>（41 字符）
        String raw = "tp-" + taskId;
        return raw.length() <= 100 ? raw : raw.substring(0, 100);
    }

    private boolean isDefaultTemplate(BasicParamPO po) {
        Object parsed = parseJson(po.getOtherParams());
        return parsed instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("is_default"));
    }

    /** 用来源的值构造一份参数（param_id 由调用方决定，task_id 不带）。 */
    private BasicParamPO copyParamValues(BasicParamPO source, String paramId) {
        BasicParamPO po = new BasicParamPO();
        po.setParamId(paramId);
        po.setParamName(source.getParamName());
        po.setBenchSegment(source.getBenchSegment());
        po.setCurrentTimepoint(source.getCurrentTimepoint());
        po.setSetName(source.getSetName());
        po.setWaterQs(source.getWaterQs());
        po.setTidalLevel(source.getTidalLevel());
        po.setBenchId(source.getBenchId());
        po.setRefId(source.getRefId());
        po.setHs(source.getHs());
        po.setHc(source.getHc());
        po.setProtectionLevel(source.getProtectionLevel());
        po.setControlLevel(source.getControlLevel());
        po.setComparisonTimepoint(source.getComparisonTimepoint());
        po.setRiskThresholds(source.getRiskThresholds());
        po.setWeights(source.getWeights());
        po.setOtherParams(source.getOtherParams());
        return po;
    }

    /** 断面快照（cross_sections 的一行）转成一份参数。 */
    private BasicParamPO fromSectionSnapshot(SectionPO section) {
        BasicParamPO po = new BasicParamPO();
        po.setParamName(section.getParamName() != null ? section.getParamName() : section.getSectionName());
        po.setBenchSegment(section.getBenchSegment());
        po.setCurrentTimepoint(section.getCurrentTimepoint());
        po.setSetName(section.getSetName());
        po.setWaterQs(section.getWaterQs());
        po.setTidalLevel(section.getTidalLevel());
        po.setBenchId(section.getBenchId());
        po.setRefId(section.getRefId());
        po.setHs(section.getHs());
        po.setHc(section.getHc());
        po.setProtectionLevel(section.getProtectionLevel());
        po.setControlLevel(section.getControlLevel());
        po.setComparisonTimepoint(section.getComparisonTimepoint());
        po.setRiskThresholds(section.getRiskThresholds());
        po.setWeights(section.getWeights());
        po.setOtherParams(section.getOtherParams());
        return po;
    }

    // ==================== Section ====================

    public SectionResponse saveSection(String taskId, SectionPayload payload, boolean inheritFromBasicParam, boolean overwrite) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        if (overwrite && sectionMapper.existsBySectionId(payload.sectionId(), userId)) {
            updateSection(payload.sectionId(), payload);
            return getSection(payload.sectionId());
        }

        Integer basicParamId = resolveTaskParamId(taskId, payload.basicParamId(), userId);
        Map<String, Object> baseParams = inheritFromBasicParam && basicParamId != null
                ? toMap(basicParamMapper.selectById(basicParamId, userId))
                : new LinkedHashMap<>();
        Map<String, Object> merged = mergeSectionParams(baseParams, payload);
        SectionPO po = toSectionPO(taskId, basicParamId, merged, payload);
        po.setUserId(SecurityUtils.getCurrentUserId());
        sectionMapper.insert(po);
            return SectionResponse.from(sectionMapper.selectBySectionId(payload.sectionId(), userId));
    }

    /**
     * 断面只能挂在本任务自己的参数集上，**绝不挂模板库主数据**。
     *
     * 旧实现是「断面 → 模板库某一行」的纯引用，于是改模板就等于改所有引用它的任务；
     * 现在即使前端仍传了模板库的 id，也先物化成本任务的参数集再挂上去。
     * 这样「引用」这条路径从数据层就不可能再出现。
     */
    private Integer resolveTaskParamId(String taskId, Integer requestedId, Long userId) {
        BasicParamPO taskParams = basicParamMapper.selectByTaskId(taskId, userId);
        if (taskParams != null) {
            return taskParams.getId().intValue();
        }

        String templateParamId = null;
        if (requestedId != null) {
            BasicParamPO requested = basicParamMapper.selectById(requestedId, userId);
            if (requested != null && requested.getTaskId() != null && !taskId.equals(requested.getTaskId())) {
                // 指向了别的任务的私有参数集 ⇒ 不接受，按默认来源物化
                templateParamId = null;
            } else if (requested != null) {
                templateParamId = requested.getParamId();
            }
        }
        return materializeTaskParams(taskId, templateParamId, userId).po().getId().intValue();
    }

    public List<SectionResponse> listSections(String taskId, String bankId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        return sectionMapper.selectByTaskIdAndBankId(taskId, bankId, userId).stream().map(SectionResponse::from).collect(Collectors.toList());
    }

    public SectionResponse getSection(String sectionId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        SectionPO po = sectionMapper.selectBySectionId(sectionId, userId);
        if (po == null) {
            throw new IllegalArgumentException("Section not found: " + sectionId);
        }
        return SectionResponse.from(po);
    }

    /**
     * {@link #updateSection} 的结果。
     *
     * @param geometryChanged 本次保存是否真的改了几何（端点坐标有变化）
     * @param hadResult       变更**之前**该断面有没有有效结果（写在这里是因为作废后查不到了）
     */
    public record SectionGeometryUpdate(boolean geometryChanged, boolean hadResult) {

        public static final SectionGeometryUpdate UNCHANGED = new SectionGeometryUpdate(false, false);
    }

    /**
     * 更新断面；几何变了就把该断面的派生数据（风险结果、剖面）一并作废。
     *
     * <p>为什么作废要放在这里，而不是只在「重算」接口里清：
     * 重算可能被拒绝（整任务在跑 / 同一断面在算）或前端根本没调成功。只要旧结果还活着，
     * 结果页就会拿过期结论给新位置着色 —— 那正是「断面挪走了、原地还在变色」。
     * 所以顺序是：先让旧结论失效（这里），再尝试算新的（recompute）。
     *
     * <p>作废是软删：{@code bank_risk_results} 保留历史行（含人工修改快照 edit_state），
     * {@code section_profiles} 下次 insertOrUpdate 会命中唯一键并复活 deleted_at。
     */
    public SectionGeometryUpdate updateSection(String sectionId, SectionPayload payload) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        SectionPO existing = sectionMapper.selectBySectionId(sectionId, userId);
        if (existing == null) {
            throw new IllegalArgumentException("Section not found: " + sectionId);
        }

        SectionPO po = toSectionPO(null, null, null, payload);
        po.setSectionId(sectionId);
        sectionMapper.update(po);

        if (!geometryChanged(existing.getSectionGeometry(), payload.sectionGeometry())) {
            return SectionGeometryUpdate.UNCHANGED;
        }

        int activeResults = riskResultMapper.countActiveBySectionId(sectionId, userId);
        riskResultMapper.deleteBySectionId(sectionId, userId);
        sectionProfileMapper.deleteBySectionId(sectionId, userId);
        log.info("[section] geometry changed, derived data invalidated, sectionId={}, droppedResults={}",
                sectionId, activeResults);
        return new SectionGeometryUpdate(true, activeResults > 0);
    }

    /**
     * 几何是否真的变了。
     *
     * <p>按 JsonNode 结构比较而不是字符串比较：jsonb 读回来是规范化文本（键序、空白都不同），
     * 逐字比会把「没动过」判成变了，于是每次改个名字都白扔一次重算。
     * 已知边界：{@code 120} 与 {@code 120.0} 是不同节点类型，会判成变了 —— 方向是安全的一侧
     * （多算一次，而不是留着过期结论）。
     *
     * <p>{@code incoming == null} 表示这次请求没带几何（只改了名字/参数），一律视为未变。
     *
     * <p>包级可见 + static 是为了能被单测直接钉住：这段判断错了，要么每次保存都白烧一次
     * 模型调用，要么旧结论永远不作废。
     */
    static boolean geometryChanged(String storedJson, Map<String, Object> incoming) {
        if (incoming == null) {
            return false;
        }
        if (storedJson == null || storedJson.isBlank()) {
            return true;
        }
        try {
            return !GEOMETRY_COMPARATOR.readTree(storedJson).equals(GEOMETRY_COMPARATOR.valueToTree(incoming));
        } catch (Exception exception) {
            // 解析不了就别乱作废：宁可留一次可能过期的结论，也不要因为一个脏字段把结果删光。
            log.warn("[section] geometry compare failed, treat as unchanged: {}", exception.getMessage());
            return false;
        }
    }

    public void reverseSection(String sectionId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        SectionPO po = sectionMapper.selectBySectionId(sectionId, userId);
        if (po == null) {
            throw new IllegalArgumentException("Section not found: " + sectionId);
        }
        String reversedGeometry = reverseLineStringJson(po.getSectionGeometry());
        String swappedOtherParams = swapLeftRightPoints(po.getOtherParams());
        sectionMapper.reverseSectionGeometry(sectionId, reversedGeometry, swappedOtherParams, userId);
    }

    private String reverseLineStringJson(String geometryJson) {
        if (geometryJson == null || geometryJson.isBlank()) {
            return geometryJson;
        }
        try {
            JsonNode root = objectMapper.readTree(geometryJson);
            if (root instanceof ObjectNode obj && obj.has("coordinates")) {
                JsonNode coords = obj.get("coordinates");
                if (coords.isArray() && coords.size() >= 2) {
                    ArrayNode reversed = objectMapper.createArrayNode();
                    for (int i = coords.size() - 1; i >= 0; i--) {
                        reversed.add(coords.get(i));
                    }
                    obj.set("coordinates", reversed);
                }
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalArgumentException("断面几何 JSON 无法解析: " + e.getMessage());
        }
    }

    private String swapLeftRightPoints(String otherParamsJson) {
        if (otherParamsJson == null || otherParamsJson.isBlank()) {
            return otherParamsJson;
        }
        try {
            JsonNode root = objectMapper.readTree(otherParamsJson);
            if (root instanceof ObjectNode obj && obj.has("leftPoint") && obj.has("rightPoint")) {
                JsonNode left = obj.get("leftPoint");
                obj.set("leftPoint", obj.get("rightPoint"));
                obj.set("rightPoint", left);
                return objectMapper.writeValueAsString(root);
            }
            return otherParamsJson;
        } catch (Exception e) {
            return otherParamsJson;
        }
    }

    public void deleteSection(String sectionId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        if (sectionMapper.deleteBySectionId(sectionId, userId) == 0) {
            throw new IllegalArgumentException("Section not found: " + sectionId);
        }
    }

    public Map<String, Object> getTaskFullData(String taskId) {
        return Map.of(
                "task", getTask(taskId),
                "sections", listSections(taskId, null));
    }

    public Map<String, Integer> clearTaskData(String taskId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        int resultCount = riskResultMapper.countByTaskId(taskId, userId);
        int sectionCount = sectionMapper.countByTaskId(taskId, userId);
        riskResultMapper.deleteByTaskId(taskId, userId);
        sectionMapper.deleteByTaskId(taskId, userId);
        return Map.of("sections", sectionCount, "results", resultCount);
    }

    public void clearTaskResults(String taskId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        riskResultMapper.deleteByTaskId(taskId, userId);
        sectionProfileMapper.deleteByTaskId(taskId, userId);
    }

    /**
     * 该任务下已有有效结果的断面 id 集合。供「只算未计算断面」判断哪些要跳过。
     *
     * <p>用 {@code selectByTaskIdAndBankIdAndRegionCode} 一次捞全（而非逐断面 count）：
     * 大任务有 2000+ 断面，逐条查会把"判断要不要算"变成比"算"还慢的事。
     */
    public Set<String> getSectionIdsWithResult(String taskId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        Set<String> ids = new HashSet<>();
        for (RiskResultPO po : riskResultMapper.selectByTaskIdAndBankIdAndRegionCode(taskId, null, null, userId)) {
            if (po != null && po.getSectionId() != null && !po.getSectionId().isBlank()) {
                ids.add(po.getSectionId());
            }
        }
        return ids;
    }

    public void batchUpdateSectionParams(List<String> sectionIds, Map<String, Object> params) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        if (sectionIds == null || sectionIds.isEmpty()) return;
        sectionMapper.batchUpdateParams(sectionIds, params, userId);
    }

    /**
     * 统计该参数模板下有多少断面的快照与模板不一致（只读，供界面提示「N 条断面未同步」）。
     * 不一致的典型来源：断面先建、模板后改。
     */
    public int countParamDiffsForTask(String taskId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        return sectionMapper.countParamDiffsForTask(taskId, userId);
    }

    /**
     * 把本任务的断面参数快照刷新为该任务自己的参数集（不是模板库）。
     *
     * 作用域**只能是任务**：模板库是主数据，一个模板被多个历史任务共用（实测模板 166 下
     * 13822 条断面横跨 15 个任务），按模板下发会一次性改写所有历史任务的输入。
     * 也不挂在 updateBasicParam 上自动执行：保存模板是主数据操作，与任务无关。
     */
    public Map<String, Object> refreshParamsForTask(String taskId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();

        // 先确保本任务有独立的参数集，再同步 —— 否则会把模板库当成任务参数来用，
        // 等于把「引用」又写回去了。
        if (basicParamMapper.selectByTaskId(taskId, userId) == null) {
            materializeTaskParams(taskId, null, userId);
        }

        int pendingBefore = sectionMapper.countParamDiffsForTask(taskId, userId);
        int updated = pendingBefore == 0 ? 0 : sectionMapper.refreshParamsForTask(taskId, userId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("task_id", taskId);
        result.put("pending_before", pendingBefore);
        result.put("updated", updated);
        return result;
    }

    // ==================== RiskResult ====================

    public List<RiskResultResponse> listRiskResults(String taskId, String bankId, String regionCode) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        return riskResultMapper.selectByTaskIdAndBankIdAndRegionCode(taskId, bankId, regionCode, userId)
                .stream().map(RiskResultResponse::from).collect(Collectors.toList());
    }

    public RiskResultResponse getRiskResultBySectionId(String sectionId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        RiskResultPO po = riskResultMapper.selectLatestBySectionId(sectionId, userId);
        if (po == null) {
            throw new IllegalArgumentException("Risk result not found for section_id: " + sectionId);
        }
        return RiskResultResponse.from(po);
    }

    /**
     * 单条结果的三态查询：{@code computed} / {@code not_computed} / 断面不存在。
     *
     * <p>为什么不能一律用 404：断面存在但没算出结果（模型因 DEM 无数据失败、或该断面
     * 是分析之后才新建的）是**正常状态**，用 404 表达会强迫前端把「还没算」渲染成
     * 一条 HTTP 错误，用户看到的是 {@code HTTP 404: Risk result not found...}，
     * 与「这条断面被删了」分不开。所以这里把「断面没了」和「断面在、结果没有」拆开：
     * 只有前者抛 {@link IllegalArgumentException}（沿用全局 404 映射）。
     */
    public Map<String, Object> getRiskResultPayload(String sectionId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        Map<String, Object> payload = new LinkedHashMap<>();
        RiskResultPO po = riskResultMapper.selectLatestBySectionId(sectionId, userId);
        if (po != null) {
            payload.put("status", "computed");
            payload.put("result", RiskResultResponse.from(po));
            return payload;
        }

        if (!sectionMapper.existsBySectionId(sectionId, userId)) {
            throw new IllegalArgumentException("Section not found for section_id: " + sectionId);
        }

        payload.put("status", "not_computed");
        payload.put("result", null);
        payload.put("reason", "该断面尚无计算结果：可能是分析之后新建的断面，或上次计算因数据问题失败，请重新执行风险分析");
        return payload;
    }

    public void saveRiskResult(
            String taskId,
            String sectionId,
            String sectionName,
            String regionCode,
            String bankId,
            Integer riskLevel,
            Map<String, Object> indicators,
            Map<String, Object> geometry) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        RiskResultPO po = new RiskResultPO();
        po.setUserId(SecurityUtils.getCurrentUserId());
        po.setTaskId(taskId);
        po.setSectionId(sectionId);
        po.setSectionName(sectionName);
        po.setRegionCode(regionCode);
        po.setBankId(bankId);
        po.setRiskLevel(riskLevel);
        po.setIndicators(writeJson(indicators));
        riskResultMapper.insert(po);
    }

    public void saveRiskResultIfAbsent(
            String runId,
            String taskId,
            String sectionId,
            Integer riskLevel,
            Map<String, Object> indicators) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        if (riskResultMapper.existsByRunIdAndSectionId(runId, sectionId, userId)) {
            return;
        }
        Map<String, Object> section = getSectionForResult(sectionId);
        RiskResultPO po = new RiskResultPO();
        po.setUserId(SecurityUtils.getCurrentUserId());
        po.setRunId(runId);
        po.setTaskId(taskId);
        po.setSectionId(sectionId);
        po.setSectionName(String.valueOf(section.get("section_name")));
        po.setRegionCode(String.valueOf(section.get("region_code")));
        po.setBankId(String.valueOf(section.get("bank_id")));
        po.setRiskLevel(riskLevel);
        po.setIndicators(writeJson(indicators));
        riskResultMapper.insert(po);
    }

    // ==================== RiskResult 人工修改 ====================

    /** 与 riskLevel.py 的 raw_values 键一致；顺序即前端表格的展示顺序。 */
    private static final List<String> OVERRIDE_INDICATOR_KEYS =
            List.of("Ky", "PQ", "Zd", "Sa", "Ln", "Zb", "Dsed", "PL", "LC");

    /** PL/LC 是枚举，其余是连续量。两套枚举字符串不能混用（PL 用 systemic，LC 用 strict）。 */
    private static final Map<String, List<String>> OVERRIDE_ENUM_VALUES = Map.of(
            "PL", List.of("systemic", "normal", "low", "no"),
            "LC", List.of("strict", "normal", "low", "no"));

    /** 结果里没记录阈值时的兜底，与前端 DEFAULT_RISK_THRESHOLDS 同值。 */
    private static final double[] DEFAULT_RISK_ALL = {0.25, 0.5, 0.75};

    /**
     * 人工修改一条风险结果。
     *
     * 只做三件事：校验不变量 → 把前端重算好的 indicators 写回同一行 → 留痕。
     * **刻意不重算评分**：重算意味着在 Java 里再写一遍 9 个指标的分档方向与矩阵点乘，
     * 与 riskLevel.py 形成第三份实现，迟早漂移。这里只校验最廉价、也最危险的那条不变量：
     * 风险值与风险等级必须自洽（bucket(result) + 1 == risk_level）。
     */
    public RiskResultResponse overrideRiskResult(String sectionId, RiskResultOverrideRequest request) {
        if (request == null || request.patch() == null || request.patch().isEmpty()) {
            throw ResultOverrideException.invalid("缺少 patch 内容");
        }
        Long dataFilterUserId = SecurityUtils.getCurrentUserIdForDataFilter();
        RiskResultPO current = riskResultMapper.selectLatestBySectionId(sectionId, dataFilterUserId);
        if (current == null) {
            throw new IllegalArgumentException("Risk result not found for section_id: " + sectionId);
        }
        if (request.taskId() != null && !request.taskId().isBlank()
                && !request.taskId().equals(current.getTaskId())) {
            throw ResultOverrideException.invalid("该断面不属于任务 " + request.taskId());
        }
        // 同一断面每跑一轮就会多插一行；客户端编辑的是它看到的那一行。
        // 若库里最新行已经不是它，说明任务重跑过，此时保存会「改了但看不见」，必须让用户刷新。
        if (request.resultId() != null && !request.resultId().equals(current.getId())) {
            throw ResultOverrideException.stale("该断面结果已被新一轮运行覆盖，请刷新后重试");
        }

        Map<String, Object> patch = request.patch();
        if (!(patch.get("raw_values") instanceof Map<?, ?> rawPatch)) {
            throw ResultOverrideException.invalid("raw_values 缺失");
        }

        Map<String, Object> newRawValues = new LinkedHashMap<>();
        for (String key : OVERRIDE_INDICATOR_KEYS) {
            if (!rawPatch.containsKey(key)) {
                throw ResultOverrideException.invalid("缺少指标 " + key);
            }
            Object value = rawPatch.get(key);
            List<String> allowed = OVERRIDE_ENUM_VALUES.get(key);
            if (allowed != null) {
                String text = value == null ? null : String.valueOf(value).trim().toLowerCase();
                if (text == null || !allowed.contains(text)) {
                    throw ResultOverrideException.invalid(
                            key + " 必须是 " + allowed + " 之一，实际为 " + value);
                }
                newRawValues.put(key, text);
            } else {
                if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
                    throw ResultOverrideException.invalid(key + " 必须是有限数值，实际为 " + value);
                }
                newRawValues.put(key, number.doubleValue());
            }
        }

        if (!(patch.get("result") instanceof Number resultNumber)) {
            throw ResultOverrideException.invalid("result 缺失");
        }
        double riskValue = resultNumber.doubleValue();
        if (!Double.isFinite(riskValue) || riskValue < 0 || riskValue > 1) {
            throw ResultOverrideException.invalid("风险值必须在 0~1 之间，实际为 " + riskValue);
        }
        if (!(patch.get("risk_level") instanceof Number levelNumber)) {
            throw ResultOverrideException.invalid("risk_level 缺失");
        }
        int riskLevel = levelNumber.intValue();
        if (riskLevel < 1 || riskLevel > 4) {
            throw ResultOverrideException.invalid("风险等级必须在 1~4 之间，实际为 " + riskLevel);
        }
        double[] all = riskThresholdsOf(current.getIndicators());
        int expectedLevel = bucketRiskLevel(riskValue, all) + 1;
        if (expectedLevel != riskLevel) {
            throw ResultOverrideException.invalid(
                    "风险值 " + riskValue + " 与风险等级 " + riskLevel + " 不一致（按阈值应为 " + expectedLevel + "）");
        }

        String note = request.note() == null ? null : request.note().trim();
        if (note != null && note.length() > 200) {
            throw ResultOverrideException.invalid("备注不能超过 200 字");
        }

        Object parsedCurrent = parseJson(current.getIndicators());
        Map<String, Object> nextIndicators = parsedCurrent instanceof Map<?, ?> currentMap
                ? asStringKeyMap(currentMap)
                : new LinkedHashMap<>();

        nextIndicators.put("raw_values", newRawValues);
        Map<String, Object> matrices = nextIndicators.get("matrices") instanceof Map<?, ?> matrixMap
                ? asStringKeyMap(matrixMap)
                : new LinkedHashMap<>();
        if (patch.get("matrices") instanceof Map<?, ?> matrixPatch) {
            matrices.putAll(asStringKeyMap(matrixPatch));
        }
        nextIndicators.put("matrices", matrices);
        nextIndicators.put("result", riskValue);
        nextIndicators.put("risk-level", oneHotRiskLevel(riskLevel));

        String now = java.time.OffsetDateTime.now().toString();
        Map<String, Object> overrideMeta = new LinkedHashMap<>();
        overrideMeta.put("edited", true);
        overrideMeta.put("edited_at", now);
        overrideMeta.put("edited_by", SecurityUtils.getCurrentUserId());
        overrideMeta.put("note", note);
        nextIndicators.put("manual_override", overrideMeta);

        // 原始快照只在**首次**修改时拍下；后续修改继续沿用，否则「恢复模型原值」会退回到上一次的人工值。
        Object existingState = parseJson(current.getEditState());
        Map<String, Object> editState = existingState instanceof Map<?, ?> stateMap
                && stateMap.get("original_indicators") != null
                ? asStringKeyMap(stateMap)
                : new LinkedHashMap<>();
        if (!editState.containsKey("original_indicators")) {
            editState.put("original_indicators", parsedCurrent);
            editState.put("original_risk_level", current.getRiskLevel());
        }
        editState.put("edited_at", now);
        editState.put("edited_by", SecurityUtils.getCurrentUserId());
        editState.put("note", note);

        int updated = riskResultMapper.updateManualOverride(
                current.getId(), writeJson(nextIndicators), riskLevel, writeJson(editState), dataFilterUserId);
        if (updated == 0) {
            throw ResultOverrideException.stale("保存失败：结果行已变化，请刷新后重试");
        }
        return RiskResultResponse.from(riskResultMapper.selectLatestBySectionId(sectionId, dataFilterUserId));
    }

    /** 恢复模型原值。返回恢复后的结果，供前端就地重绘。 */
    public RiskResultResponse revertRiskResultOverride(String sectionId, String taskId) {
        Long dataFilterUserId = SecurityUtils.getCurrentUserIdForDataFilter();
        RiskResultPO current = riskResultMapper.selectLatestBySectionId(sectionId, dataFilterUserId);
        if (current == null) {
            throw new IllegalArgumentException("Risk result not found for section_id: " + sectionId);
        }
        if (taskId != null && !taskId.isBlank() && !taskId.equals(current.getTaskId())) {
            throw ResultOverrideException.invalid("该断面不属于任务 " + taskId);
        }
        int updated = riskResultMapper.revertManualOverride(current.getId(), dataFilterUserId);
        if (updated == 0) {
            throw ResultOverrideException.invalid("该结果没有人工修改记录");
        }
        return RiskResultResponse.from(riskResultMapper.selectLatestBySectionId(sectionId, dataFilterUserId));
    }

    /** 取该结果自带的 all 阈值；缺失（老记录/自定义参数未回带）时回落默认。 */
    private double[] riskThresholdsOf(String indicatorsJson) {
        Object parsed = parseJson(indicatorsJson);
        if (parsed instanceof Map<?, ?> map) {
            Object thresholds = map.get("thresholds");
            if (thresholds instanceof Map<?, ?> thresholdMap) {
                Object all = thresholdMap.get("risk_threshold");
                if (all instanceof List<?> list && list.size() >= 3
                        && list.get(0) instanceof Number a && list.get(1) instanceof Number b
                        && list.get(2) instanceof Number c) {
                    return new double[]{a.doubleValue(), b.doubleValue(), c.doubleValue()};
                }
            }
        }
        return DEFAULT_RISK_ALL;
    }

    /** 与 riskLevel.py 的 `risk_value < risk_threshold[i]` 完全同语义。 */
    private int bucketRiskLevel(double riskValue, double[] all) {
        if (riskValue < all[0]) return 0;
        if (riskValue < all[1]) return 1;
        if (riskValue < all[2]) return 2;
        return 3;
    }

    private List<Integer> oneHotRiskLevel(int riskLevel) {
        List<Integer> oneHot = new java.util.ArrayList<>(4);
        for (int tier = 1; tier <= 4; tier++) {
            oneHot.add(tier == riskLevel ? 1 : 0);
        }
        return oneHot;
    }

    private Map<String, Object> asStringKeyMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    // ==================== SectionProfile ====================

    public List<SectionProfileResponse> listSectionProfiles(String taskId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        return sectionProfileMapper.selectByTaskId(taskId, userId)
                .stream().map(SectionProfileResponse::from).collect(Collectors.toList());
    }

    public SectionProfileResponse getSectionProfile(String sectionId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        SectionProfilePO po = sectionProfileMapper.selectLatestBySectionId(sectionId, userId);
        if (po == null) {
            throw new IllegalArgumentException("Section profile not found for section_id: " + sectionId);
        }
        return SectionProfileResponse.from(po);
    }

    public void saveSectionProfile(
            String taskId,
            String sectionId,
            String sectionName,
            String regionCode,
            String bankId,
            String demId,
            String sourceCaseId,
            Number interval,
            Integer deepestIndex,
            Integer slopeFootIndex,
            Integer pointCount,
            Map<String, Object> profileData,
            Map<String, Object> geometry) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        SectionProfilePO po = new SectionProfilePO();
        po.setUserId(SecurityUtils.getCurrentUserId());
        po.setTaskId(taskId);
        po.setSectionId(sectionId);
        po.setSectionName(sectionName);
        po.setRegionCode(regionCode);
        po.setBankId(bankId);
        po.setDemId(demId);
        po.setSourceCaseId(sourceCaseId);
        po.setInterval(interval != null ? interval.doubleValue() : null);
        po.setDeepestIndex(deepestIndex);
        po.setSlopeFootIndex(slopeFootIndex);
        po.setPointCount(pointCount);
        po.setProfileData(writeJson(profileData));
        sectionProfileMapper.insertOrUpdate(po);
    }

    // ==================== Helper Methods ====================

    public List<Map<String, Object>> getSectionsByTask(String taskId) {
        return sectionMapper.selectByTaskIdAndBankId(taskId, null, SecurityUtils.getCurrentUserIdForDataFilter())
                .stream().map(this::toMap).collect(Collectors.toList());
    }

    public Map<String, Object> getSectionForResult(String sectionId) {
        return toMap(sectionMapper.selectBySectionId(sectionId, SecurityUtils.getCurrentUserIdForDataFilter()));
    }

    // ==================== PO <-> Map Conversion (仅内部使用，公共 API 已改用 DTO) ====================

    private Map<String, Object> toMap(BankPO po) {
        if (po == null) return null;
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", po.getId());
        map.put("bank_id", po.getBankId());
        map.put("bank_name", po.getBankName());
        map.put("region_code", po.getReachGroup());
        map.put("bank_geometry", parseJson(po.getBankGeometry()));
        map.put("reversed", po.getReversed());
        map.put("description", po.getDescription());
        map.put("created_at", po.getCreatedAt());
        map.put("updated_at", po.getUpdatedAt());
        return map;
    }

    // 由 getSectionForResult / saveSection 内部分支使用
    private Map<String, Object> toMap(SectionPO po) {
        if (po == null) return null;
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", po.getId());
        map.put("task_id", po.getTaskId());
        map.put("section_id", po.getSectionId());
        map.put("section_name", po.getSectionName());
        map.put("bank_id", po.getBankId());
        map.put("region_code", po.getRegionCode());
        map.put("segment_index", po.getSegmentIndex());
        map.put("section_geometry", parseJson(po.getSectionGeometry()));
        map.put("vertical_foot_point", parseJson(po.getVerticalFootPoint()));
        map.put("distance", po.getDistance());
        map.put("basic_param_id", po.getBasicParamId());
        map.put("param_name", po.getParamName());
        map.put("bench_segment", po.getBenchSegment());
        map.put("current_timepoint", po.getCurrentTimepoint());
        map.put("set_name", po.getSetName());
        map.put("water_qs", po.getWaterQs());
        map.put("tidal_level", po.getTidalLevel());
        map.put("bench_id", po.getBenchId());
        map.put("ref_id", po.getRefId());
        map.put("hs", po.getHs());
        map.put("hc", po.getHc());
        map.put("protection_level", po.getProtectionLevel());
        map.put("control_level", po.getControlLevel());
        map.put("comparison_timepoint", po.getComparisonTimepoint());
        map.put("risk_thresholds", parseJson(po.getRiskThresholds()));
        map.put("weights", parseJson(po.getWeights()));
        map.put("other_params", parseJson(po.getOtherParams()));
        map.put("is_valid", po.getIsValid());
        map.put("validation_status", po.getValidationStatus());
        map.put("validation_message", po.getValidationMessage());
        map.put("created_at", po.getCreatedAt());
        map.put("updated_at", po.getUpdatedAt());
        return map;
    }

    // 由 saveSection 的参数继承分支使用
    private Map<String, Object> toMap(BasicParamPO po) {
        if (po == null) return null;
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", po.getId());
        map.put("param_id", po.getParamId());
        map.put("param_name", po.getParamName());
        map.put("bench_segment", po.getBenchSegment());
        map.put("current_timepoint", po.getCurrentTimepoint());
        map.put("set_name", po.getSetName());
        map.put("water_qs", po.getWaterQs());
        map.put("tidal_level", po.getTidalLevel());
        map.put("bench_id", po.getBenchId());
        map.put("ref_id", po.getRefId());
        map.put("hs", po.getHs());
        map.put("hc", po.getHc());
        map.put("protection_level", po.getProtectionLevel());
        map.put("control_level", po.getControlLevel());
        map.put("comparison_timepoint", po.getComparisonTimepoint());
        map.put("risk_thresholds", parseJson(po.getRiskThresholds()));
        map.put("weights", parseJson(po.getWeights()));
        map.put("other_params", parseJson(po.getOtherParams()));
        map.put("created_at", po.getCreatedAt());
        map.put("updated_at", po.getUpdatedAt());
        return map;
    }

    // ==================== Payload -> PO Conversion ====================

    private BankPO toBankPO(BankPayload payload) {
        BankPO po = new BankPO();
        po.setBankId(payload.bankId());
        po.setBankName(payload.bankName());
        po.setReachGroup(payload.reachGroup());
        po.setBankGeometry(writeJson(payload.bankGeometry()));
        po.setDescription(payload.description());
        po.setReversed(payload.reversed() != null && payload.reversed());
        return po;
    }

    private TaskPO toTaskPO(TaskPayload payload) {
        TaskPO po = new TaskPO();
        po.setTaskName(payload.taskName());
        po.setBankIds(writeJson(payload.bankIds()));
        po.setDescription(payload.description());
        return po;
    }

    private BasicParamPO toBasicParamPO(BasicParamPayload payload) {
        BasicParamPO po = new BasicParamPO();
        po.setParamId(payload.paramId());
        po.setParamName(payload.paramName());
        po.setBenchSegment(payload.benchSegment());
        po.setCurrentTimepoint(payload.currentTimepoint());
        po.setSetName(payload.setName());
        po.setWaterQs(payload.waterQs());
        po.setTidalLevel(payload.tidalLevel());
        po.setBenchId(payload.benchId());
        po.setRefId(payload.refId());
        po.setHs(payload.hs());
        po.setHc(payload.hc());
        po.setProtectionLevel(payload.protectionLevel());
        po.setControlLevel(payload.controlLevel());
        po.setComparisonTimepoint(payload.comparisonTimepoint());
        po.setRiskThresholds(writeJson(payload.riskThresholds()));
        po.setWeights(writeJson(payload.weights()));
        po.setOtherParams(writeJson(payload.otherParams()));
        return po;
    }

    private SectionPO toSectionPO(String taskId, Integer basicParamId, Map<String, Object> merged, SectionPayload payload) {
        SectionPO po = new SectionPO();
        po.setTaskId(taskId);
        po.setSectionId(payload.sectionId());
        po.setSectionName(payload.sectionName());
        po.setBankId(payload.bankId());
        po.setRegionCode(payload.regionCode());
        po.setSegmentIndex(payload.segmentIndex());
        po.setSectionGeometry(writeJson(payload.sectionGeometry()));
        po.setVerticalFootPoint(writeJson(merged != null ? merged.get("vertical_foot_point") : payload.verticalFootPoint()));
        po.setDistance(payload.distance());
        po.setBasicParamId(basicParamId);
        po.setParamName(merged != null ? (String) merged.get("param_name") : payload.paramName());
        po.setBenchSegment(merged != null ? (String) merged.get("bench_segment") : payload.benchSegment());
        po.setCurrentTimepoint(merged != null ? (String) merged.get("current_timepoint") : payload.currentTimepoint());
        po.setSetName(merged != null ? (String) merged.get("set_name") : payload.setName());
        po.setWaterQs(merged != null ? (String) merged.get("water_qs") : payload.waterQs());
        po.setTidalLevel(merged != null ? (String) merged.get("tidal_level") : payload.tidalLevel());
        po.setBenchId(merged != null ? (String) merged.get("bench_id") : payload.benchId());
        po.setRefId(merged != null ? (String) merged.get("ref_id") : payload.refId());
        po.setHs(merged != null ? toDouble(merged.get("hs")) : payload.hs());
        po.setHc(merged != null ? toDouble(merged.get("hc")) : payload.hc());
        po.setProtectionLevel(merged != null ? (String) merged.get("protection_level") : payload.protectionLevel());
        po.setControlLevel(merged != null ? (String) merged.get("control_level") : payload.controlLevel());
        po.setComparisonTimepoint(merged != null ? (String) merged.get("comparison_timepoint") : payload.comparisonTimepoint());
        po.setRiskThresholds(writeJson(merged != null ? merged.get("risk_thresholds") : payload.riskThresholds()));
        po.setWeights(writeJson(merged != null ? merged.get("weights") : payload.weights()));
        po.setOtherParams(writeJson(merged != null ? merged.get("other_params") : payload.otherParams()));
        return po;
    }

    // ==================== Utility Methods ====================

    private Map<String, Object> mergeSectionParams(Map<String, Object> baseParams, SectionPayload payload) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (baseParams != null) {
            merged.putAll(baseParams);
        }
        putIfNotNull(merged, "segment_index", payload.segmentIndex());
        putIfNotNull(merged, "vertical_foot_point", payload.verticalFootPoint());
        putIfNotNull(merged, "distance", payload.distance());
        putIfNotNull(merged, "param_name", payload.paramName());
        putIfNotNull(merged, "bench_segment", payload.benchSegment());
        putIfNotNull(merged, "current_timepoint", payload.currentTimepoint());
        putIfNotNull(merged, "set_name", payload.setName());
        putIfNotNull(merged, "water_qs", payload.waterQs());
        putIfNotNull(merged, "tidal_level", payload.tidalLevel());
        putIfNotNull(merged, "bench_id", payload.benchId());
        putIfNotNull(merged, "ref_id", payload.refId());
        putIfNotNull(merged, "hs", payload.hs());
        putIfNotNull(merged, "hc", payload.hc());
        putIfNotNull(merged, "protection_level", payload.protectionLevel());
        putIfNotNull(merged, "control_level", payload.controlLevel());
        putIfNotNull(merged, "comparison_timepoint", payload.comparisonTimepoint());
        putIfNotNull(merged, "risk_thresholds", payload.riskThresholds());
        putIfNotNull(merged, "weights", payload.weights());
        putIfNotNull(merged, "other_params", payload.otherParams());
        return merged;
    }

    private void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private Double toDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }

    private String writeJson(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to write JSON", e);
        }
    }

    private Object parseJson(String json) {
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }
}
