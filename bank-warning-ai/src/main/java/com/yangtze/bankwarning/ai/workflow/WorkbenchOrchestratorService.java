package com.yangtze.bankwarning.ai.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangtze.bankwarning.ai.domain.po.WorkbenchConfigPO;
import com.yangtze.bankwarning.ai.dto.WorkbenchRunRequest;
import com.yangtze.bankwarning.ai.mapper.WorkbenchConfigMapper;
import com.yangtze.bankwarning.security.security.SecurityUtils;
import io.agentscope.core.model.Model;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.memory.MemoryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class WorkbenchOrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(WorkbenchOrchestratorService.class);

    private final Model model;
    private final Toolkit toolkit;
    private final List<AgentSkillRepository> skillRepos;
    private final WorkbenchConfigMapper configMapper;
    private final ObjectMapper objectMapper;
    private final Path baseWorkspace;

    public WorkbenchOrchestratorService(
            Model deepseekModel,
            Toolkit reportToolkit,
            List<AgentSkillRepository> agentSkillRepositories,
            WorkbenchConfigMapper configMapper,
            ObjectMapper objectMapper) {
        this.model = deepseekModel;
        this.toolkit = reportToolkit;
        this.skillRepos = agentSkillRepositories;
        this.configMapper = configMapper;
        this.objectMapper = objectMapper;
        this.baseWorkspace = Path.of(".agentscope", "workspace", "workbench").toAbsolutePath();
    }

    public Map<String, Object> save(WorkbenchRunRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        try {
            WorkbenchConfigPO po = new WorkbenchConfigPO();
            po.setUserId(userId);
            po.setTitle(request.title() != null && !request.title().isBlank()
                    ? request.title() : ("方案 " + java.time.LocalDateTime.now().toString().substring(0, 16)));
            po.setConfigJson(objectMapper.writeValueAsString(request));
            configMapper.insert(po);
            log.info("[workbench] saved config id={} for user {}", po.getId(), userId);
            return Map.of("success", true, "id", po.getId(), "message", "编排方案已保存");
        } catch (JsonProcessingException e) {
            log.error("[workbench] failed to serialize config", e);
            return Map.of("success", false, "error", "序列化失败: " + e.getMessage());
        }
    }

    public List<Map<String, Object>> listConfigs() {
        Long userId = SecurityUtils.getCurrentUserId();
        return configMapper.selectByUserId(userId).stream()
                .map(po -> Map.<String, Object>of("id", po.getId(), "title", po.getTitle(), "created_at", po.getCreatedAt().toString()))
                .collect(Collectors.toList());
    }

    public Map<String, Object> getConfig(Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        WorkbenchConfigPO po = configMapper.selectById(id, userId);
        if (po == null) return Map.of("success", false, "error", "方案不存在");
        return Map.of("success", true, "id", po.getId(), "config", po.getConfigJson(), "createdAt", po.getCreatedAt().toString());
    }

    public Map<String, Object> deleteConfig(Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        int deleted = configMapper.deleteById(id, userId);
        if (deleted == 0) return Map.of("success", false, "error", "方案不存在或无权限");
        return Map.of("success", true, "message", "已删除");
    }

    public Map<String, Object> run(WorkbenchRunRequest request) {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        log.info("[workbench] run {} started, agents={}, tasks={}", runId, request.agents().size(), request.tasks().size());

        try {
            writeSubagentDefinitions(request);
            HarnessAgent orchestrator = buildOrchestrator(request);
            String fullPrompt = buildPrompt(request);
            var result = orchestrator.call(fullPrompt).block();

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("runId", runId);
            response.put("output", result != null ? result.getTextContent() : "");
            return response;
        } catch (Exception e) {
            log.error("[workbench] run {} failed", runId, e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    public Map<String, Object> runLatest() {
        Long userId = SecurityUtils.getCurrentUserId();
        List<WorkbenchConfigPO> list = configMapper.selectByUserId(userId);
        if (list.isEmpty()) return Map.of("success", false, "error", "请先保存编排方案");
        try {
            WorkbenchRunRequest request = objectMapper.readValue(list.get(0).getConfigJson(), WorkbenchRunRequest.class);
            return run(request);
        } catch (Exception e) {
            log.error("[workbench] failed to load saved config", e);
            return Map.of("success", false, "error", "加载保存方案失败: " + e.getMessage());
        }
    }

    private void writeSubagentDefinitions(WorkbenchRunRequest request) throws IOException {
        Path subagentsDir = baseWorkspace.resolve("subagents");
        Files.createDirectories(subagentsDir);

        for (var agent : request.agents()) {
            if ("chief".equals(agent.id())) continue;

            StringBuilder md = new StringBuilder();
            md.append("---\n");
            md.append("description: ").append(agent.desc() != null ? agent.desc() : agent.name() + "专用智能体").append("\n");
            md.append("workspace:\n  mode: shared\n");
            if (agent.skills() != null && !agent.skills().isEmpty()) {
                md.append("skills: [").append(String.join(", ", agent.skills())).append("]\n");
            }
            md.append("---\n");
            md.append("你是").append(agent.name()).append("，请根据总工程师的指派完成任务。\n");
            if (agent.desc() != null && !agent.desc().isBlank()) {
                md.append("\n职能说明：").append(agent.desc()).append("\n");
            }

            Path mdFile = subagentsDir.resolve(agent.name() + ".md");
            Files.writeString(mdFile, md.toString());
            log.info("[workbench] wrote subagent spec: {}", mdFile);
        }
    }

    private HarnessAgent buildOrchestrator(WorkbenchRunRequest request) {
        Path wsPath = baseWorkspace.resolve("runs").resolve(UUID.randomUUID().toString().substring(0, 8));
        var builder = HarnessAgent.builder()
                .name("总工程师")
                .model(model)
                .workspace(wsPath)
                .abstractFilesystem(new LocalFilesystem(wsPath))
                .toolkit(toolkit)
                .skillRepositories(skillRepos)
                .maxIters(20)
                .memory(MemoryConfig.builder()
                        .flushTrigger(MemoryConfig.FlushTrigger.throttled(java.time.Duration.ofMinutes(5)))
                        .sessionRetentionDays(7)
                        .build())
                .disableFilesystemTools()
                .disableShellTool();

        return builder.build();
    }

    private String buildPrompt(WorkbenchRunRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是总工程师，负责统筹调度团队完成风险预警任务。\n\n");

        List<WorkbenchRunRequest.AgentSpec> members = request.agents().stream()
                .filter(a -> !"chief".equals(a.id())).toList();
        if (!members.isEmpty()) {
            sb.append("当前可用子智能体：\n");
            for (var a : members) {
                sb.append("- ").append(a.name());
                if (a.desc() != null && !a.desc().isBlank()) {
                    sb.append("（").append(a.desc()).append("）");
                }
                sb.append("\n");
            }
            sb.append("\n调度规则：\n");
            sb.append("1. 使用 agent_spawn 工具将任务委派给合适的子智能体\n");
            sb.append("2. 每个子智能体的 agent_id 就是其名称\n");
            sb.append("3. 等待子智能体完成后，基于结果汇总输出\n\n");
        }

        if (!request.tasks().isEmpty()) {
            sb.append("当前任务列表：\n");
            for (var t : request.tasks()) {
                sb.append("- ").append(t.name()).append("\n");
            }
            sb.append("\n");
        }

        if (!request.reports().isEmpty()) {
            sb.append("当前报告列表：\n");
            for (var r : request.reports()) {
                sb.append("- ").append(r.name()).append("\n");
            }
            sb.append("\n");
        }

        sb.append("用户指令：").append(request.prompt() != null ? request.prompt() : "请调度团队完成任务");
        return sb.toString();
    }
}
