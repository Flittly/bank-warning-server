package com.yangtze.bankwarning.service;

import com.yangtze.bankwarning.config.TerrainMappingProperties;
import com.yangtze.bankwarning.domain.exception.SectionRecomputeException;
import com.yangtze.bankwarning.service.async.TaskDispatchPort;
import com.yangtze.bankwarning.service.async.TaskRunStatePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.yangtze.bankwarning.dto.kafka.ModelTask;
import org.springframework.beans.factory.annotation.Value;

import java.time.Instant;
import java.util.UUID;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TaskExecutionService {

    private static final Logger log = LoggerFactory.getLogger(TaskExecutionService.class);

    private final BusinessStoreService businessStoreService;
    private final ModelGatewayService modelGatewayService;
    private final TaskDispatchPort taskDispatchPort;
    private final TaskRunStatePort taskRunStatePort;
    private final TerrainMappingProperties terrainMappingProperties;
    private final SectionProfileService sectionProfileService;
    private final TaskCancelRegistry taskCancelRegistry;
    private final SectionRecomputeGuard sectionRecomputeGuard;

    @Value("${app.kafka.enabled:false}")
    private boolean kafkaEnabled;

    public TaskExecutionService(
            BusinessStoreService businessStoreService,
            ModelGatewayService modelGatewayService,
            TaskDispatchPort taskDispatchPort,
            TaskRunStatePort taskRunStatePort,
            TerrainMappingProperties terrainMappingProperties,
            SectionProfileService sectionProfileService,
            TaskCancelRegistry taskCancelRegistry,
            SectionRecomputeGuard sectionRecomputeGuard) {
        this.businessStoreService = businessStoreService;
        this.modelGatewayService = modelGatewayService;
        this.taskDispatchPort = taskDispatchPort;
        this.taskRunStatePort = taskRunStatePort;
        this.terrainMappingProperties = terrainMappingProperties;
        this.sectionProfileService = sectionProfileService;
        this.taskCancelRegistry = taskCancelRegistry;
        this.sectionRecomputeGuard = sectionRecomputeGuard;
    }

    public Map<String, Object> runTask(String taskId) {
        return runTask(taskId, false);
    }

    /**
     * 执行任务下的断面。
     *
     * @param onlyPending false（默认）= 历史行为：先软删本任务全部结果，再全量重算。
     *                    true = **只算还没有有效结果的断面**，已有结果原样保留。
     *                    用于「给任务补充了岸段，只想算新增的那几条」——否则补 2 条岸段
     *                    要把上百条老断面一起重跑，代价完全不成比例。
     */
    public Map<String, Object> runTask(String taskId, boolean onlyPending) {
        return runTask(taskId, onlyPending, null);
    }

    /**
     * 同上，但可以用 {@code sectionIds} 把范围收窄到「这一批断面」。
     *
     * <p>为什么需要它：{@code only_pending=true} 的语义是"整个任务里所有还没有结果的断面"。
     * 给一个跑了 300 条、只出了 40 条结果的任务补 13 条断面，它会连带把 275 条老断面
     * 一起重算 —— 与界面上「只算本次新增」的承诺不符，用户会以为卡死而中途取消，
     * 然后看到"补了岸段却没有结果"。传了 section_ids 就只算这一批（仍尊重已有结果跳过）。
     */
    public Map<String, Object> runTask(String taskId, boolean onlyPending, List<String> sectionIds) {
        log.info("[task-run] starting task run, taskId={}, onlyPending={}, scope={}",
                taskId, onlyPending, sectionIds == null ? "all" : sectionIds.size());
        businessStoreService.getTask(taskId);
        // 必须在 clearTaskResults **之前**问：这一步会把该任务全部结果软删，
        // 若此刻有条断面正在单独重算，它刚落的新结果会被顺手抹掉。
        if (sectionRecomputeGuard.hasInFlightForTask(taskId)) {
            throw SectionRecomputeException.taskHasRunningSection(taskId);
        }

        List<Map<String, Object>> sections = businessStoreService.getSectionsByTask(taskId);
        int totalSections = sections.size();
        int skippedExisting = 0;
        if (onlyPending) {
            // 增量模式**不清结果**：清了就不是增量了。只把"已有结论"的断面筛掉。
            // sectionIds 非空时再叠加一层"只算这一批"的限制（补岸段后只跑新增断面）。
            Set<String> scope = (sectionIds == null || sectionIds.isEmpty())
                    ? null
                    : new HashSet<>(sectionIds);
            Set<String> alreadyDone = businessStoreService.getSectionIdsWithResult(taskId);
            List<Map<String, Object>> pending = new ArrayList<>();
            int skippedOutOfScope = 0;
            for (Map<String, Object> section : sections) {
                String sid = String.valueOf(section.get("section_id"));
                if (scope != null && !scope.contains(sid)) {
                    skippedOutOfScope++;
                    continue;
                }
                if (alreadyDone.contains(sid)) {
                    skippedExisting++;
                } else {
                    pending.add(section);
                }
            }
            sections = pending;
            log.info("[task-run] incremental run, taskId={}, total={}, pending={}, skippedExisting={}, skippedOutOfScope={}",
                    taskId, totalSections, sections.size(), skippedExisting, skippedOutOfScope);

            if (sections.isEmpty()) {
                // 没有待算断面就别把任务打成 running：那会让结果页的进度条一直转，
                // 也会让「终止」按钮出现在一个根本没在跑的任务上。
                return Map.of(
                        "success", true,
                        "task_id", taskId,
                        "status", "nothing_pending",
                        "results", List.of(),
                        "failures", List.of(),
                        "summary", Map.of(
                                "total", totalSections,
                                "success", 0,
                                "failed", 0,
                                "skipped", 0,
                                "skipped_existing", skippedExisting,
                                "skipped_out_of_scope", skippedOutOfScope));
            }
        } else {
            log.info("[task-run] clearing old results, taskId={}", taskId);
            businessStoreService.clearTaskResults(taskId);
        }

        log.info("[task-run] marking running, taskId={}", taskId);
        businessStoreService.markTaskRunning(taskId);
        // 登记进中断表：从这里开始到 finally 注销为止，本进程"确实在跑"这个任务。
        // 取消接口靠这张表区分「真在跑（可协作式停）」与「进程已死的僵尸（直接改库收尾）」。
        taskCancelRegistry.register(taskId);

        try {
            log.info("[task-run] loaded sections, taskId={}, sectionCount={}", taskId, sections.size());
            List<Map<String, Object>> results = new ArrayList<>();
            List<Map<String, Object>> failures = new ArrayList<>();

            boolean cancelRequested = false;
            boolean lostOwnership = false;
            for (Map<String, Object> section : sections) {
                // 协作式取消的唯一检查点：断面之间。刻意不打断正在进行的模型调用 ——
                // 既保证"已算完的断面结果不丢"，也让停止时机可解释（最迟当前这条算完就停）。
                //
                // 这一次调用同时办三件事：续期租约（声明"我还活着"）、查取消标志、确认自己仍是
                // 这个任务的所有者。合并成一次是因为三者必须同时成立才有意义 —— 分开查会有缝隙，
                // 而且断面本身要算几秒，多一次往返不划算。
                TaskCancelRegistry.CancelSignal signal = taskCancelRegistry.heartbeatAndCheck(taskId);
                if (signal == TaskCancelRegistry.CancelSignal.LOST_OWNERSHIP) {
                    // 租约已被别的实例接管（同一任务被再启动一次），或本实例续不上期。
                    // 此时必须停手：继续算下去会和对方同时往同一批断面写结果。
                    lostOwnership = true;
                    log.warn("[task-run] lost ownership, stop without writing final status, taskId={}, done={}",
                            taskId, results.size() + failures.size());
                    break;
                }
                if (signal == TaskCancelRegistry.CancelSignal.CANCEL_REQUESTED) {
                    cancelRequested = true;
                    log.info("[task-run] cancel requested, stop before next section, taskId={}, done={}",
                            taskId, results.size() + failures.size());
                    break;
                }
                String sectionId = String.valueOf(section.get("section_id"));
                try {
                    results.add(computeAndSaveSection(taskId, section));
                } catch (Exception sectionException) {
                    log.error("[task-run] section failed, taskId={}, sectionId={}, message={}",
                            taskId,
                            sectionId,
                            sectionException.getMessage(),
                            sectionException);
                    Map<String, Object> errorInfo = classifyError(sectionException);
                    Map<String, Object> failed = new LinkedHashMap<>();
                    failed.put("section_id", section.get("section_id"));
                    failed.put("status", 500);
                    failed.put("error_code", errorInfo.get("error_code"));
                    failed.put("error_message", errorInfo.get("error_message"));
                    failed.put("error_detail", sectionException.getMessage());
                    failed.put("executed_at", Instant.now().toString());
                    failures.add(failed);
                }
            }

            // 失去所有权 ⇒ 直接返回，**不写任何终态**。
            // 终态归接管者写；这里若顺手写个 cancelled / completed，就会与对方互相覆盖，
            // 那正是「两个写入者打架」的老问题换了个入口。
            // 返回 status=superseded 只是为了让人看得懂发生了什么（前端不识别这个值时会走未知分支，
            // 不会崩；且这个分支在单写者部署下不会触发）。
            if (lostOwnership) {
                return Map.of(
                        "success", false,
                        "task_id", taskId,
                        "status", "superseded",
                        "results", results,
                        "failures", failures,
                        "summary", Map.of(
                                "total", sections.size(),
                                "success", results.size(),
                                "failed", failures.size(),
                                "skipped", Math.max(0, sections.size() - results.size() - failures.size())
                        ));
            }

            // 终止优先于任何终态。用「或」而不是只看循环里那个布尔量：取消请求可能刚好落在
            // 最后一次检查之后（循环已正常跑完），此时若不补这一下，本方法返回前写下的
            // completed / partial_failed 会盖掉取消接口刚写下的 cancelled —— 那就是"假停止"的翻版。
            if (cancelRequested || taskCancelRegistry.isCancelRequested(taskId)) {
                int handled = results.size() + failures.size();
                String cancelMessage = String.format(
                        "已由用户手动终止：成功 %d 个断面、失败 %d 个，%d 个未处理；已完成的结果全部保留。",
                        results.size(), failures.size(), Math.max(0, sections.size() - handled));
                businessStoreService.markTaskCancelled(taskId, cancelMessage);
                log.info("[task-run] task cancelled, taskId={}, completed={}, failed={}, total={}",
                        taskId, results.size(), failures.size(), sections.size());
                return Map.<String, Object>of(
                        "success", false,
                        "task_id", taskId,
                        "status", "cancelled",
                        "results", results,
                        "failures", failures,
                        "summary", Map.of(
                                "total", sections.size(),
                                "success", results.size(),
                                "failed", failures.size(),
                                "skipped", Math.max(0, sections.size() - handled),
                                "skipped_existing", skippedExisting
                        ));
            }

            String finalStatus;
            if (failures.isEmpty()) {
                finalStatus = "completed";
                businessStoreService.markTaskCompleted(taskId);
            } else if (results.isEmpty()) {
                finalStatus = "error";
                businessStoreService.markTaskError(taskId, joinFailureMessages(failures));
            } else {
                finalStatus = "partial_failed";
                businessStoreService.markTaskPartialFailed(taskId, joinFailureMessages(failures));
            }

            log.info("[task-run] task finished, taskId={}, successCount={}, failureCount={}, finalStatus={}",
                    taskId,
                    results.size(),
                    failures.size(),
                    finalStatus);
            return Map.of(
                    "success", !results.isEmpty(),
                    "task_id", taskId,
                    "status", finalStatus,
                    "results", results,
                    "failures", failures,
                    "summary", Map.of(
                            "total", sections.size(),
                            "success", results.size(),
                            "failed", failures.size(),
                            "skipped_existing", skippedExisting
                    ));
        } catch (Exception exception) {
            log.error("[task-run] task failed, taskId={}, message={}", taskId, exception.getMessage(), exception);
            businessStoreService.markTaskError(taskId, exception.getMessage());
            throw exception;
        } finally {
            // 必须注销：留着的话取消接口会把"其实已经跑完的任务"判成仍在跑（cooperative），
            // 于是它只置标志、不写库，界面上那个 running 就再也没人收了。
            taskCancelRegistry.unregister(taskId);
        }
    }

    /**
     * 终止一个正在运行的任务。
     *
     * <p>两条路径分开处理，这是这个接口唯一需要小心的地方：
     * <ol>
     *   <li><b>cooperative</b> —— 登记表里还有这个任务的租约，说明确实有实例在跑它
     *       （单实例时是本进程的内存表，集群模式下是 Redis 里的租约键，见 {@link TaskCancelRegistry}）：
     *       只置标志并立即返回，**由那个循环自己写终态**。刻意不在这里写库，
     *       是为了保住"终态只有一个写入者"这条不变量（否则两个写入者会互相盖）。</li>
     *   <li><b>stale</b> —— 登记表里没有任何实例持有它，说明跑它的进程已经不在了（后端重启 / 请求被中断），
     *       也就是面板上那种挂了几个月的僵尸。此时**必须由本接口收尾**，
     *       否则它永远停在 running，而且前端还会因为它一直"运行中"而永远轮询。
     *       注意这条分支的正确性完全依赖"登记表是全局的" —— 内存表在多实例下会让它误伤正在跑的任务。</li>
     * </ol>
     */
    public Map<String, Object> cancelTask(String taskId) {
        log.info("[task-cancel] cancel requested, taskId={}", taskId);
        var task = businessStoreService.getTask(taskId);
        String status = task.status() == null ? "" : task.status().trim();

        if (!"running".equalsIgnoreCase(status)) {
            log.info("[task-cancel] task is not running, nothing to cancel, taskId={}, status={}", taskId, status);
            return Map.of(
                    "success", true,
                    "task_id", taskId,
                    "cancel_requested", false,
                    "mode", "not-running",
                    "status", status,
                    "message", "该任务当前不是运行中状态，无需终止");
        }

        if (taskCancelRegistry.requestCancel(taskId)) {
            log.info("[task-cancel] cooperative cancel armed, taskId={}", taskId);
            return Map.of(
                    "success", true,
                    "task_id", taskId,
                    "cancel_requested", true,
                    "mode", "cooperative",
                    "status", "running",
                    "message", "已请求终止：当前断面算完后停止，已完成的结果保留");
        }

        String staleMessage = "执行进程已不存在（后端可能已重启或请求已中断），"
                + "本次运行没有产生任何计算结果，已直接标记为终止。";
        businessStoreService.markTaskCancelled(taskId, staleMessage);
        log.warn("[task-cancel] stale running task marked cancelled, taskId={}", taskId);
        return Map.of(
                "success", true,
                "task_id", taskId,
                "cancel_requested", true,
                "mode", "stale",
                "status", "cancelled",
                "message", staleMessage);
    }

    /**
     * 算一条断面并把结论落库。
     *
     * <p>{@link #runTask} 的循环体与 {@link #recomputeSection} 都走这里，这是本项目
     * 「只有一份计算链路」的落点：模型参数组装、模型调用、剖面落库、结果写入全在这一段，
     * 将来改公式口径、加参数、换模型接口都只需要改一次 —— 单断面重算不会算出一套
     * 与整任务运行不同的结果。
     *
     * @return 与任务返回体里单条 result 同形状的 Map（含 risk_level，供单条重算直接回给前端）
     */
    private Map<String, Object> computeAndSaveSection(String taskId, Map<String, Object> section) {
        String sectionId = String.valueOf(section.get("section_id"));
        validateSectionGeometry(taskId, sectionId, section.get("section_geometry"));
        log.info("[section-compute] processing section, taskId={}, sectionId={}, sectionName={}",
                taskId, sectionId, section.get("section_name"));

        Map<String, Object> payload = buildRiskLevelPayload(section);
        log.info("[section-compute] built model payload, taskId={}, sectionId={}, payloadKeys={}",
                taskId, sectionId, payload.keySet());
        Map<String, Object> rawResult = modelGatewayService.runLegacyModelAndWait(
                "/v0/mi/risk-level",
                payload,
                null);
        log.info("[section-compute] model returned, taskId={}, sectionId={}, resultKeys={}",
                taskId, sectionId, rawResult.keySet());

        rawResult.put("water_qs", section.get("water_qs"));
        rawResult.put("tidal_level", section.get("tidal_level"));
        Integer riskLevel = toRiskLevel(rawResult.get("risk-level"));
        log.info("[section-compute] parsed risk level, taskId={}, sectionId={}, riskLevel={}",
                taskId, sectionId, riskLevel);

        sectionProfileService.saveForSection(taskId, section);

        businessStoreService.saveRiskResult(
                taskId,
                sectionId,
                String.valueOf(section.get("section_name")),
                String.valueOf(section.get("region_code")),
                String.valueOf(section.get("bank_id")),
                riskLevel,
                rawResult,
                castMap(section.get("geometry")));
        log.info("[section-compute] saved risk result, taskId={}, sectionId={}", taskId, sectionId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("section_id", sectionId);
        result.put("section_name", section.get("section_name"));
        result.put("status", 200);
        result.put("risk_level", riskLevel);
        result.put("response", rawResult);
        result.put("executed_at", Instant.now().toString());
        return result;
    }

    /**
     * 单断面重算：只算这一条并覆盖它的结论。
     *
     * <p>与整任务运行的关系：
     * <ul>
     *   <li><b>不复用</b> {@link TaskCancelRegistry} —— 那张表按 taskId 登记，兼职当
     *       「本进程真在跑这个任务」的心跳探针。单算若借 taskId 注册，收尾时的
     *       {@code unregister} 会把整任务的登记一并删掉，取消接口随即把它判成僵尸
     *       （stale）并直接写 cancelled。</li>
     *   <li><b>不可取消</b>：单条只算一次（模型侧有超时兜底），强行给它加取消语义
     *       要么污染上面那张表，要么引入第二套运行态；代价大于收益。整任务取消
     *       只作用于 {@link #runTask} 的断面循环，这是刻意的边界。</li>
     *   <li><b>与整任务互斥</b>（双向）：整任务跑着时拒绝单算；有单算在途时拒绝整任务起跑。
     *       两个方向都会互相覆盖对方的行，快速失败比排队更诚实。</li>
     * </ul>
     *
     * <p>失败不抛异常而是回 {@code status:"failed"} + 错误码：几何已经保存成功了，
     * 「这条算不出来」是数据问题（DEM 无数据等），不该让前端把它当成请求失败。
     */
    public Map<String, Object> recomputeSection(String sectionId) {
        Map<String, Object> section = businessStoreService.getSectionForResult(sectionId);
        if (section == null) {
            throw new IllegalArgumentException("Section not found: " + sectionId);
        }
        String taskId = String.valueOf(section.get("task_id"));

        if (taskCancelRegistry.isRunning(taskId)) {
            throw SectionRecomputeException.taskRunning(taskId, sectionId);
        }
        if (!sectionRecomputeGuard.tryAcquire(sectionId, taskId)) {
            throw SectionRecomputeException.sectionBusy(sectionId);
        }

        log.info("[section-recompute] start, taskId={}, sectionId={}", taskId, sectionId);
        try {
            Map<String, Object> result = computeAndSaveSection(taskId, section);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("success", true);
            payload.put("status", "computed");
            payload.put("task_id", taskId);
            payload.put("section_id", sectionId);
            payload.put("risk_level", result.get("risk_level"));
            payload.put("response", result.get("response"));
            payload.put("executed_at", result.get("executed_at"));
            log.info("[section-recompute] done, taskId={}, sectionId={}, riskLevel={}",
                    taskId, sectionId, result.get("risk_level"));
            return payload;
        } catch (Exception exception) {
            Map<String, Object> errorInfo = classifyError(exception);
            log.error("[section-recompute] failed, taskId={}, sectionId={}, message={}",
                    taskId, sectionId, exception.getMessage(), exception);
            Map<String, Object> failed = new LinkedHashMap<>();
            failed.put("success", false);
            failed.put("status", "failed");
            failed.put("task_id", taskId);
            failed.put("section_id", sectionId);
            failed.put("error_code", errorInfo.get("error_code"));
            failed.put("error_message", errorInfo.get("error_message"));
            failed.put("error_detail", exception.getMessage() == null ? "" : exception.getMessage());
            return failed;
        } finally {
            sectionRecomputeGuard.release(sectionId);
        }
    }

    private Map<String, Object> buildRiskLevelPayload(Map<String, Object> section) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("segment", section.get("bench_segment"));
        payload.put("current-timepoint", section.get("current_timepoint"));
        payload.put("set", section.get("set_name"));
        payload.put("water-qs", section.get("water_qs"));
        payload.put("tidal-level", section.get("tidal_level"));
        payload.put("bench-id", section.get("bench_id"));
        payload.put("ref-id", section.get("ref_id"));
        payload.put("hs", section.get("hs"));
        payload.put("hc", section.get("hc"));
        payload.put("protection-level", section.get("protection_level"));
        payload.put("control-level", section.get("control_level"));
        payload.put("comparison-timepoint", section.get("comparison_timepoint"));
        payload.put("risk-thresholds", section.get("risk_thresholds") == null ? "NONE" : section.get("risk_thresholds"));
        payload.put("section-geometry", section.get("section_geometry"));

        Map<String, Object> weights = castMap(section.get("weights"));
        payload.put("wRE", weights.getOrDefault("wRE", "NONE"));
        payload.put("wNM", weights.getOrDefault("wNM", "NONE"));
        payload.put("wGE", weights.getOrDefault("wGE", "NONE"));
        payload.put("wRL", weights.getOrDefault("wRL", "NONE"));
        return payload;
    }

    private Integer toRiskLevel(Object riskLevelValue) {
        if (riskLevelValue instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) {
                Object item = list.get(index);
                if (item instanceof Number number && number.intValue() == 1) {
                    return index + 1;
                }
            }
        }
        return 0;
    }

    private Map<String, Object> castMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return new LinkedHashMap<>();
    }
    public Map<String, Object> submitTaskRun(String taskId) {
        if (!kafkaEnabled) {
            throw new IllegalStateException("Kafka模式未启用，请设置环境变量 KAFKA_ENABLED=true");
        }

        log.info("[task-submit-kafka] 开始提交任务 taskId={}", taskId);

        // 1. 校验任务存在
        businessStoreService.getTask(taskId);
        // 与 runTask 同理：下一步的清空会抹掉在途单算刚落的结果。
        if (sectionRecomputeGuard.hasInFlightForTask(taskId)) {
            throw SectionRecomputeException.taskHasRunningSection(taskId);
        }
        log.info("[task-submit-kafka] 任务存在，清理旧结果 taskId={}", taskId);

        // 2. 清理本次运行旧结果
        businessStoreService.clearTaskResults(taskId);
        log.info("[task-submit-kafka] 旧结果已清理，标记任务运行中 taskId={}", taskId);

        // 3. 标记任务状态为 running
        businessStoreService.markTaskRunning(taskId);

        try {
            List<Map<String, Object>> sections = businessStoreService.getSectionsByTask(taskId);
            log.info("[task-submit-kafka] 加载断面完成 taskId={} sectionCount={}",
                    taskId, sections.size());

            // 4. 生成 runId
            String runId = taskRunStatePort.createRun(taskId, sections.size());

            // 5. 遍历断面，发送到 Kafka
            for (Map<String, Object> section : sections) {
                String sectionId = String.valueOf(section.get("section_id"));
                String bankId = String.valueOf(section.get("bank_id"));
                validateSectionGeometry(taskId, sectionId, section.get("section_geometry"));
                log.info("[task-submit-kafka] 发送断面到 Kafka taskId={} sectionId={} bankId={}",
                        taskId, sectionId, bankId);

                // 从数据库获取岸段信息，读取 terrain_key
                Map<String, Object> bank = businessStoreService.getBank(bankId);
                String terrainKey = String.valueOf(bank.get("terrain_key"));
                String terrainBucket = terrainMappingProperties.getTerrainBucket();
                
                // 如果数据库中没有 terrain_key，使用默认格式
                if (terrainKey == null || "null".equals(terrainKey) || terrainKey.isBlank()) {
                    terrainKey = "tiff/" + bankId + ".tif";
                    log.info("[task-submit-kafka] 数据库无地形映射，使用默认格式: bankId={} -> terrainKey={}", bankId, terrainKey);
                } else {
                    log.info("[task-submit-kafka] 从数据库获取地形映射: bankId={} -> terrainKey={}", bankId, terrainKey);
                }

                // 构造任务消息
                Map<String, Object> payload = buildRiskLevelPayload(section);
                ModelTask modelTask = new ModelTask();
                modelTask.setRunId(runId);
                modelTask.setTaskId(taskId);
                modelTask.setSectionId(sectionId);
                modelTask.setBankId(bankId);
                modelTask.setRegionCode(String.valueOf(section.get("region_code")));
                modelTask.setModelType("risk-level");
                modelTask.setTerrainBucket(terrainBucket);  // 设置 RustFS bucket
                modelTask.setTerrainKey(terrainKey);         // 设置地形文件路径
                modelTask.setPayload(payload);
                modelTask.setSubmittedAt(Instant.now());
                modelTask.setTraceId(UUID.randomUUID().toString());
                modelTask.setRetryCount(0);

                // 发送到 Kafka（Key 为 bankId，保证同一岸段的任务由同一 Worker 处理）
                taskDispatchPort.send(modelTask);
            }

            log.info("[task-submit-kafka] 所有断面已发送到 Kafka taskId={} runId={}", taskId, runId);

            return Map.of(
                    "success", true,
                    "taskId", taskId,
                    "runId", runId,
                    "status", "SUBMITTED",
                    "expectedCount", sections.size());

        } catch (Exception exception) {
            log.error("[task-submit-kafka] 任务提交失败 taskId={} message={}",
                    taskId, exception.getMessage(), exception);
            businessStoreService.markTaskError(taskId, exception.getMessage());
            throw exception;
        }
    }

    private void validateSectionGeometry(String taskId, String sectionId, Object sectionGeometryObject) {
        if (!(sectionGeometryObject instanceof Map<?, ?> rawMap)) {
            throw new IllegalArgumentException(String.format(
                    "Invalid section_geometry for taskId=%s sectionId=%s: payload is not an object",
                    taskId,
                    sectionId));
        }

        Map<String, Object> geometry = castMap(rawMap);
        Object type = geometry.get("type");
        if (!(type instanceof String typeValue) || !"LineString".equalsIgnoreCase(typeValue)) {
            throw new IllegalArgumentException(String.format(
                    "Invalid section_geometry for taskId=%s sectionId=%s: type must be LineString",
                    taskId,
                    sectionId));
        }

        Object coordinatesObject = geometry.get("coordinates");
        if (!(coordinatesObject instanceof List<?> coordinates) || coordinates.size() < 2) {
            throw new IllegalArgumentException(String.format(
                    "Invalid section_geometry for taskId=%s sectionId=%s: coordinates must contain at least 2 points",
                    taskId,
                    sectionId));
        }

        if (!hasNonZeroSegment(taskId, sectionId, coordinates)) {
            throw new IllegalArgumentException(String.format(
                    "Invalid section_geometry for taskId=%s sectionId=%s: all line segments are zero-length",
                    taskId,
                    sectionId));
        }
    }

    private boolean hasNonZeroSegment(String taskId, String sectionId, List<?> coordinates) {
        Double previousX = null;
        Double previousY = null;

        for (Object coordinate : coordinates) {
            if (!(coordinate instanceof List<?> point) || point.size() < 2) {
                throw new IllegalArgumentException(String.format(
                        "Invalid section_geometry for taskId=%s sectionId=%s: coordinate point must contain at least [x, y]",
                        taskId,
                        sectionId));
            }

            Double currentX = toDouble(point.get(0));
            Double currentY = toDouble(point.get(1));
            if (currentX == null || currentY == null) {
                throw new IllegalArgumentException(String.format(
                        "Invalid section_geometry for taskId=%s sectionId=%s: coordinate values must be numeric",
                        taskId,
                        sectionId));
            }

            if (previousX != null && previousY != null) {
                double deltaX = currentX - previousX;
                double deltaY = currentY - previousY;
                if ((deltaX * deltaX + deltaY * deltaY) > 0d) {
                    return true;
                }
            }

            previousX = currentX;
            previousY = currentY;
        }

        return false;
    }

    private Double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String joinFailureMessages(List<Map<String, Object>> failures) {
        if (failures.isEmpty()) {
            return null;
        }
        return failures.stream()
                .map(item -> String.format("section_id=%s error_code=%s error=%s",
                        item.get("section_id"),
                        item.get("error_code"),
                        item.get("error_message")))
                .collect(Collectors.joining(" | "));
    }

    /**
     * 把断面级异常翻成 {@code error_code + error_message}。
     *
     * <p>包级可见 + static 是为了能被单测直接调用：这套映射决定了「用户看到的是
     * DEM 无数据还是未知错误」，而它过去把模型侧给得明明白白的
     * DEM_COVERAGE_INSUFFICIENT 统一吞成 UNKNOWN_ERROR，是最值得钉住的一段逻辑。
     * 无实例状态，做成静态不会引入隐式耦合。
     */
    static Map<String, Object> classifyError(Exception exception) {
        String message = exception.getMessage();
        if (message == null) {
            message = "";
        }

        // 先按模型侧给的稳定错误码匹配，再退化到文本特征。顺序不能反：
        // 「DEM 无数据」的消息里也含 "scatter points are insufficient" 一类字样，
        // 让文本规则先命中会把它错判成「几何数据无效」，把数据问题说成输入问题。
        if (message.contains("DEM_COVERAGE_INSUFFICIENT")) {
            return Map.of("error_code", "DEM_COVERAGE_INSUFFICIENT",
                    "error_message", "断面落在 DEM 无数据（nodata）区域内，可采样的有效剖面长度不足；请检查该位置的 DEM 覆盖，或改选有效区域内的断面");
        }
        if (message.contains("SECTION_PROFILE_DEGENERATE")) {
            return Map.of("error_code", "SECTION_PROFILE_DEGENERATE",
                    "error_message", "该断面剖面退化（最深点位于首个采样点，左岸坡段为空），无法提取垂线剖面；请调整断面走向或位置");
        }
        if (message.contains("SECTION_VERTICAL_PROFILE_EMPTY")) {
            return Map.of("error_code", "SECTION_VERTICAL_PROFILE_EMPTY",
                    "error_message", "该断面的垂线剖面为空，无法计算风险；请检查断面与 DEM 的对应关系");
        }
        if (message.contains("DEM_COVERAGE") || message.contains("nodata")) {
            return Map.of("error_code", "DEM_COVERAGE_INSUFFICIENT",
                    "error_message", "断面与 DEM 有效区域不匹配，剖面采样失败；请检查 DEM 覆盖范围");
        }

        if (message.contains("LineString Is Invalid") || message.contains("scatter points are insufficient")) {
            return Map.of("error_code", "GEOMETRY_INVALID", "error_message", "断面几何数据无效，无法生成采样点");
        }
        if (message.contains("Invalid section_geometry")) {
            return Map.of("error_code", "GEOMETRY_INVALID", "error_message", "断面几何格式不合法");
        }
        if (message.contains("Feature Collection Is Not Allowed")) {
            return Map.of("error_code", "GEOMETRY_FORMAT_ERROR", "error_message", "不支持 FeatureCollection 格式，请使用 LineString");
        }
        if (message.contains("Section View") && message.contains("error")) {
            return Map.of("error_code", "SECTION_VIEW_FAILED", "error_message", "断面剖面计算失败，请检查 DEM 数据");
        }
        if (message.contains("timed out") || message.contains("Timeout")) {
            return Map.of("error_code", "MODEL_TIMEOUT", "error_message", "模型计算超时，请稍后重试");
        }
        if (message.contains("Could not connect") || message.contains("Connection refused")) {
            return Map.of("error_code", "MODEL_UNAVAILABLE", "error_message", "模型服务不可用，请检查服务状态");
        }
        if (message.contains("TIFF file not found") || message.contains("Resource not found")) {
            return Map.of("error_code", "TIFF_NOT_FOUND", "error_message", "DEM 数据文件不存在，请检查数据配置");
        }
        if (message.contains("Task not found")) {
            return Map.of("error_code", "TASK_NOT_FOUND", "error_message", "任务不存在");
        }
        if (message.contains("Section not found")) {
            return Map.of("error_code", "SECTION_NOT_FOUND", "error_message", "断面数据不存在");
        }
        if (exception instanceof IllegalArgumentException) {
            return Map.of("error_code", "INVALID_INPUT", "error_message", "输入参数无效：" + message);
        }

        return Map.of("error_code", "UNKNOWN_ERROR", "error_message", "计算过程中发生未知错误");
    }
}
