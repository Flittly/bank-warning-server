package com.yangtze.bankwarning.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangtze.bankwarning.domain.dto.TaskCopyRequest;
import com.yangtze.bankwarning.domain.dto.TaskCopyResponse;
import com.yangtze.bankwarning.domain.exception.TaskCopyException;
import com.yangtze.bankwarning.domain.po.TaskPO;
import com.yangtze.bankwarning.mapper.BankMapper;
import com.yangtze.bankwarning.mapper.BasicParamMapper;
import com.yangtze.bankwarning.mapper.RiskResultMapper;
import com.yangtze.bankwarning.mapper.SectionMapper;
import com.yangtze.bankwarning.mapper.SectionProfileMapper;
import com.yangtze.bankwarning.mapper.TaskMapper;
import com.yangtze.bankwarning.security.security.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 任务复制。
 *
 * <p>复制的语义是「把一份可重跑的输入搬成独立的新任务」，不是「克隆数据库行」：
 * <ul>
 *   <li><b>复制</b>：{@code tasks} 元数据 + {@code bank_ids} + 任务私有参数集 + 全部断面
 *       （几何、参数快照、校验结论）；</li>
 *   <li><b>默认不复制</b>：计算结果与剖面 —— 同一份输入可由模型确定性重算，而搬结果会让
 *       「这批结论是谁算的、谁改的」脱离上下文（真要搬就 {@code include_results=true}，
 *       并落 {@code source_task_id} 标记）；</li>
 *   <li><b>不复制</b>：岸段主数据、DEM、水动力数据集、流场纹理、模型 case 缓存、报告运行记录。
 *       前四者是共享资源（按 id 引用即可，搬一次 3.1 GB 纹理），case 缓存是只读的，
 *       副本共享它才是复制的性能红利（同 payload ⇒ 同 md5 ⇒ 首次运行近秒回）。</li>
 * </ul>
 *
 * <p><b>整段在一个事务里</b>：半个副本（有任务没断面，或断面缺一半）会立刻出现在资源树里，
 * 用户既不敢删也不敢用。断面/结果/剖面用 {@code INSERT … SELECT} 在库内搬，见各 Mapper 注释。
 *
 * <p><b>三个 id 全部重生成</b>：{@code task_id}（{@code task-<uuid>}）、
 * 每条 {@code section_id}（{@code sec-<新 taskId><原后缀>}）、参数行 {@code param_id}
 * （{@code tp-<新 taskId>}）。三个 UNIQUE 约束都挂在旧 id 上，复用即覆盖原件。
 */
@Service
public class TaskCopyService {

    private static final Logger log = LoggerFactory.getLogger(TaskCopyService.class);

    /** 缺省名称后缀。任务名允许重复，加后缀只是为了让用户一眼看出哪条是副本。 */
    private static final String DEFAULT_NAME_SUFFIX = " - 副本";

    /** `tasks.task_name` 是 varchar(100)。 */
    private static final int MAX_TASK_NAME_LENGTH = 100;

    /**
     * 客户端指定 `target_task_id` 时的长度上限。
     *
     * <p>不是 100：`basic_params.param_id` 也是 varchar(100)，复制时会拼 `tp-` 前缀（2 字符），
     * 再留给 section_id（`sec-` 4 字符）一些余量，97 是三个前缀都放得下的安全值。
     */
    private static final int MAX_TASK_ID_LENGTH = 97;

    /** 与前端 `utils/taskId.ts` 同款格式（`task-` + UUID v4），不要新造 id 形态。 */
    private static final Pattern TASK_ID_PATTERN =
            Pattern.compile("^task-[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$");

    /** 自由断面手工画线产生的占位 id：`banks` 表没有记录，写进 bank_ids 会让加载任务时 404。 */
    private static final String SYNTHETIC_BANK_PREFIX = "line-";

    private static final DateTimeFormatter DESCRIPTION_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final TaskMapper taskMapper;
    private final BankMapper bankMapper;
    private final BasicParamMapper basicParamMapper;
    private final SectionMapper sectionMapper;
    private final RiskResultMapper riskResultMapper;
    private final SectionProfileMapper sectionProfileMapper;
    private final BusinessStoreService businessStoreService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TaskCopyService(TaskMapper taskMapper,
                           BankMapper bankMapper,
                           BasicParamMapper basicParamMapper,
                           SectionMapper sectionMapper,
                           RiskResultMapper riskResultMapper,
                           SectionProfileMapper sectionProfileMapper,
                           BusinessStoreService businessStoreService) {
        this.taskMapper = taskMapper;
        this.bankMapper = bankMapper;
        this.basicParamMapper = basicParamMapper;
        this.sectionMapper = sectionMapper;
        this.riskResultMapper = riskResultMapper;
        this.sectionProfileMapper = sectionProfileMapper;
        this.businessStoreService = businessStoreService;
    }

    /**
     * 复制一个任务。
     *
     * @param sourceTaskId 源任务 id（必须对当前用户可见）
     * @param request      可选参数，null 等同默认策略
     * @throws IllegalArgumentException 源任务不存在 / 不可见（全局映射为 404）
     * @throws TaskCopyException        入参不合法（400）、id 撞车或在跑（409）、越权（403）
     */
    @Transactional
    public TaskCopyResponse copy(String sourceTaskId, TaskCopyRequest request) {
        long startedAt = System.currentTimeMillis();
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();

        TaskPO source = taskMapper.selectByTaskId(sourceTaskId, userId);
        if (source == null) {
            // 与 getTask 一致：别人的任务也是「不存在」，不做存在性泄露
            throw new IllegalArgumentException("Task not found: " + sourceTaskId);
        }

        Long targetUserId = resolveTargetUserId(request);
        List<TaskCopyResponse.Warning> warnings = new ArrayList<>();

        String newTaskId = resolveNewTaskId(request == null ? null : request.targetTaskId(), sourceTaskId);
        TaskPO occupied = taskMapper.selectByTaskIdIncludingDeleted(newTaskId, userId);
        if (occupied != null) {
            // 幂等重试：同一个 target_task_id 第二次调用，且确实是这条源任务的副本 ⇒ 直接回现状
            if (sourceTaskId.equals(occupied.getSourceTaskId()) && occupied.getDeletedAt() == null) {
                return new TaskCopyResponse(true, newTaskId, occupied.getTaskName(), sourceTaskId,
                        countsOf(newTaskId, userId), warnings, true,
                        System.currentTimeMillis() - startedAt);
            }
            // 已软删的行也占着 id（UNIQUE 不带 deleted_at 条件）。刻意不「复活」：
            // 用户删掉的任务不该因为一次复制请求就带着新内容回到列表里。
            throw TaskCopyException.conflict("TASK_ID_CONFLICT",
                    "任务 id 已被占用（可能是一条已删除但未清理的任务）：" + newTaskId);
        }

        boolean includeResults = request != null && Boolean.TRUE.equals(request.includeResults());
        boolean includeParams = request == null || !Boolean.FALSE.equals(request.includeParams());
        boolean resetValidation = request != null && Boolean.TRUE.equals(request.resetValidation());

        String sourceStatus = source.getStatus() == null ? "" : source.getStatus();
        boolean sourceRunning = "running".equalsIgnoreCase(sourceStatus);
        if (sourceRunning && includeResults) {
            // 结果集不完整时复制出来的「全量结果」是骗人的，宁可拒绝
            throw TaskCopyException.conflict("SOURCE_RUNNING",
                    "源任务正在运行，结果不完整，不能复制计算结果；可改用 include_results=false 只复制配置");
        }
        if (sourceRunning) {
            warnings.add(new TaskCopyResponse.Warning("SOURCE_RUNNING",
                    "源任务正在运行，副本仅复制配置，不含在途结果"));
        }

        // ---- 岸段关联：剔占位 id 与失效 id ----
        List<String> candidateBanks = readBankIds(source.getBankIds()).stream()
                .filter(id -> !id.isBlank())
                .filter(id -> !id.startsWith(SYNTHETIC_BANK_PREFIX))
                .distinct()
                .toList();
        Set<String> aliveBanks = candidateBanks.isEmpty()
                ? Set.of()
                : new HashSet<>(bankMapper.selectExistingBankIds(candidateBanks, userId));
        List<String> keptBanks = candidateBanks.stream().filter(aliveBanks::contains).toList();
        List<String> missingBanks = candidateBanks.stream().filter(id -> !aliveBanks.contains(id)).toList();
        if (!missingBanks.isEmpty()) {
            warnings.add(new TaskCopyResponse.Warning("BANK_MISSING",
                    missingBanks.size() + " 条岸段在库中不存在或已删除，已从副本关联中剔除：" + preview(missingBanks)));
        }

        // ---- 任务行：status / run_* 交给 DB 默认值（pending / NULL）----
        String taskName = truncateTaskName(resolveTaskName(request, source));
        TaskPO po = new TaskPO();
        po.setTaskId(newTaskId);
        po.setTaskName(taskName);
        po.setBankIds(writeJson(keptBanks));
        po.setDescription(buildDescription(source, sourceTaskId));
        po.setUserId(targetUserId);
        po.setSourceTaskId(sourceTaskId);
        po.setCopiedAt(LocalDateTime.now());
        taskMapper.insert(po);

        // ---- 参数集：先确保源任务有一份，再复制成新任务自己的 ----
        Integer newParamId = null;
        int paramCount = 0;
        if (includeParams) {
            BusinessStoreService.TaskParamsCopy copied =
                    businessStoreService.copyTaskParamsInto(sourceTaskId, newTaskId, targetUserId);
            if (copied.param() != null) {
                newParamId = copied.param().getId().intValue();
                paramCount = 1;
            }
            if (copied.param() != null && !"task".equals(copied.source())) {
                warnings.add(new TaskCopyResponse.Warning("PARAM_MATERIALIZED",
                        "源任务没有独立参数集，已按其「" + copied.source() + "」来源物化后复制"));
            }
        }

        // ---- 断面：一条 INSERT…SELECT ----
        String srcPrefix = "sec-" + sourceTaskId;
        int sectionCount = sectionMapper.copySectionsFromTask(
                sourceTaskId, newTaskId, srcPrefix, newParamId, targetUserId, resetValidation);

        // ---- 可选：结果与剖面 ----
        int resultCount = 0;
        int profileCount = 0;
        if (includeResults) {
            resultCount = riskResultMapper.copyFromTask(sourceTaskId, newTaskId, srcPrefix, targetUserId);
            profileCount = sectionProfileMapper.copyFromTask(sourceTaskId, newTaskId, srcPrefix, targetUserId);
            if (resultCount == 0) {
                warnings.add(new TaskCopyResponse.Warning("NO_RESULTS", "源任务没有可复制的计算结果"));
            }
        }

        // ---- 收尾：用副本断面反推真实岸段，补齐 bank_ids ----
        // 源 bank_ids 可能缺项（历史数据里「有关联没断面」「有断面没登记」都出现过），
        // 副本断面是全的 ⇒ 不刷新会出现「树里岸段比地图少」的老问题。
        LinkedHashSet<String> merged = new LinkedHashSet<>(keptBanks);
        merged.addAll(sectionMapper.selectDistinctRealBankIdsByTask(newTaskId, userId));
        taskMapper.updateBankIds(newTaskId, writeJson(new ArrayList<>(merged)), userId);

        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("params", paramCount);
        counts.put("sections", sectionCount);
        counts.put("results", resultCount);
        counts.put("profiles", profileCount);

        log.info("[task-copy] {} -> {} counts={} includeResults={} warnings={} elapsed={}ms",
                sourceTaskId, newTaskId, counts, includeResults, warnings.size(),
                System.currentTimeMillis() - startedAt);

        return new TaskCopyResponse(true, newTaskId, taskName, sourceTaskId, counts, warnings, false,
                System.currentTimeMillis() - startedAt);
    }

    // ==================== 内部 ====================

    /**
     * 跨用户复制的闸门。
     *
     * <p>当前数据隔离是「按 user_id 硬隔离」，没有共享/授权表 —— 允许普通用户指定目标用户
     * 等于给了一个越权写入他人数据空间的接口。P1 放开时在这里加角色判断（ADMIN / SUPER_ADMIN，
     * 两个都要判，见 {@code SecurityUtils} 的注释）。
     */
    private Long resolveTargetUserId(TaskCopyRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        if (request == null || request.targetUserId() == null) {
            return currentUserId;
        }
        if (!Objects.equals(request.targetUserId(), currentUserId)) {
            throw TaskCopyException.forbidden("跨用户复制尚未开放（需要先有共享/授权模型）");
        }
        return currentUserId;
    }

    /** 包内可见 + 静态：id 合法性三条（格式 / 长度 / 自我复制）靠单测锁死，不靠人工回归。 */
    static String resolveNewTaskId(String targetTaskId, String sourceTaskId) {        if (targetTaskId == null || targetTaskId.isBlank()) {
            return "task-" + UUID.randomUUID();
        }
        String candidate = targetTaskId.trim();
        if (candidate.equals(sourceTaskId)) {
            throw TaskCopyException.invalid("target_task_id 不能等于源任务 id（复制必须是新任务）");
        }
        if (!TASK_ID_PATTERN.matcher(candidate).matches()) {
            throw TaskCopyException.invalid("target_task_id 格式不合法（应为 task-<uuid>）：" + candidate);
        }
        if (candidate.length() > MAX_TASK_ID_LENGTH) {
            throw TaskCopyException.invalid("target_task_id 过长（最多 " + MAX_TASK_ID_LENGTH + " 字符）");
        }
        return candidate;
    }

    private String resolveTaskName(TaskCopyRequest request, TaskPO source) {
        String requested = request == null ? null : request.taskName();
        if (requested != null && !requested.isBlank()) {
            return requested.trim();
        }
        String base = source.getTaskName() == null ? "" : source.getTaskName();
        return base + DEFAULT_NAME_SUFFIX;
    }

    /**
     * 超长截断时**保住后缀**：`很长的名字… - 副本`，而不是把「 - 副本」切掉只剩一段无意义前缀。
     *
     * <p>包内可见 + 静态，便于单测直接锁这条规则（`tasks.task_name` 是 varchar(100)，
     * 超长会在 update 数据库时报错，属于「用户输入什么都能触发」的一类）。
     */
    static String truncateTaskName(String name) {
        if (name.length() <= MAX_TASK_NAME_LENGTH) {
            return name;
        }
        if (name.endsWith(DEFAULT_NAME_SUFFIX)) {
            int keep = MAX_TASK_NAME_LENGTH - DEFAULT_NAME_SUFFIX.length();
            return name.substring(0, keep) + DEFAULT_NAME_SUFFIX;
        }
        return name.substring(0, MAX_TASK_NAME_LENGTH);
    }

    /** 追加来源行。不写 `tasks.description` 之外的地方，用户后续改名也抹不掉 `source_task_id` 列。 */
    private String buildDescription(TaskPO source, String sourceTaskId) {
        String base = source.getDescription() == null ? "" : source.getDescription().trim();
        String line = "复制自 " + sourceTaskId + "（" + LocalDateTime.now().format(DESCRIPTION_TS) + "）";
        return base.isEmpty() ? line : base + "\n" + line;
    }

    private List<String> readBankIds(String bankIdsJson) {
        if (bankIdsJson == null || bankIdsJson.isBlank()) {
            return List.of();
        }
        try {
            var node = objectMapper.readTree(bankIdsJson);
            if (!node.isArray()) {
                return List.of();
            }
            List<String> ids = new ArrayList<>();
            node.forEach(item -> {
                if (item != null && !item.isNull()) {
                    String id = item.asText("").trim();
                    if (!id.isEmpty()) {
                        ids.add(id);
                    }
                }
            });
            return ids;
        } catch (Exception ex) {
            log.warn("[task-copy] bank_ids 解析失败，按空处理: {}", ex.getMessage());
            return List.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("序列化失败: " + ex.getMessage(), ex);
        }
    }

    /** 幂等重试时回真实行数：不猜、不缓存，直接查副本自己。 */
    private Map<String, Integer> countsOf(String taskId, Long userId) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        // 参数集一任务一行（uq_basic_params_task_id），这里只关心「有没有」
        counts.put("params", basicParamMapper.selectByTaskId(taskId, userId) == null ? 0 : 1);
        counts.put("sections", sectionMapper.countByTaskId(taskId, userId));
        counts.put("results", riskResultMapper.countByTaskId(taskId, userId));
        counts.put("profiles", sectionProfileMapper.countByTaskId(taskId, userId));
        return counts;
    }

    private String preview(List<String> ids) {
        int limit = 5;
        String head = String.join("、", ids.subList(0, Math.min(limit, ids.size())));
        return ids.size() > limit ? head + " 等" : head;
    }
}
