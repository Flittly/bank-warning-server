package com.yangtze.bankwarning.ai.service;

import com.yangtze.bankwarning.ai.domain.AiModelPO;
import com.yangtze.bankwarning.mapper.AiModelMapper;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.MemoryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ModelService {

    private static final Logger log = LoggerFactory.getLogger(ModelService.class);
    private final AiModelMapper mapper;
    private final Map<String, Model> modelCache = new ConcurrentHashMap<>();
    private final Map<String, HarnessAgent> agentCache = new ConcurrentHashMap<>();

    public ModelService(AiModelMapper mapper) {
        this.mapper = mapper;
    }

    public List<AiModelPO> listModels() {
        return mapper.selectAll();
    }

    public AiModelPO addModel(String modelKey, String label, String apiKey, String baseUrl, String modelName) {
        AiModelPO po = new AiModelPO();
        po.setModelKey(modelKey);
        po.setLabel(label);
        po.setApiKey(apiKey);
        po.setBaseUrl(baseUrl);
        po.setModelName(modelName);
        po.setIsDefault(false);
        mapper.insert(po);
        return po;
    }

    public void deleteModel(String modelKey) {
        mapper.deleteByKey(modelKey);
        modelCache.remove(modelKey);
        agentCache.remove(modelKey);
    }

    public Model getOrCreateModel(String modelKey) {
        return modelCache.computeIfAbsent(modelKey, k -> {
            AiModelPO po = mapper.selectByKey(k);
            if (po == null) return null;
            return OpenAIChatModel.builder()
                    .apiKey(po.getApiKey())
                    .baseUrl(po.getBaseUrl())
                    .modelName(po.getModelName())
                    .stream(true)
                    .build();
        });
    }

    public HarnessAgent getOrCreateAgent(String modelKey, HarnessAgent template) {
        if (modelKey == null || modelKey.isBlank()) return template;
        return agentCache.computeIfAbsent(modelKey, k -> {
            Model model = getOrCreateModel(k);
            if (model == null) return null;
            Path wsPath = Path.of(".agentscope", "workspace", "chat").toAbsolutePath();
            HarnessAgent agent = HarnessAgent.builder()
                    .name("ChatAgent-" + k)
                    .agentId("chat-agent-" + k)
                    .model(model)
                    .workspace(wsPath)
                    .abstractFilesystem(new io.agentscope.harness.agent.filesystem.local.LocalFilesystem(wsPath))
                    .toolkit(template.getToolkit())
                    .skillRepositories(template.getSkillRepositories())
                    .maxIters(15)
                    .memory(MemoryConfig.builder()
                            .flushTrigger(MemoryConfig.FlushTrigger.throttled(java.time.Duration.ofMinutes(5)))
                            .sessionRetentionDays(36500)
                            .build())
                    .disableFilesystemTools()
                    .disableShellTool()
                    .disableSubagents()
                    .build();
            log.info("[ModelService] created agent for: {}", k);
            return agent;
        });
    }
}
