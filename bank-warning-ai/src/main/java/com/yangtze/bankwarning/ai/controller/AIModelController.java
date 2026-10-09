package com.yangtze.bankwarning.ai.controller;

import com.yangtze.bankwarning.ai.domain.po.AiModelPO;
import com.yangtze.bankwarning.ai.service.ModelService;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v0/bank/ai/models")
public class AIModelController {

    private final ModelService modelService;

    public AIModelController(ModelService modelService) {
        this.modelService = modelService;
    }

    @GetMapping
    public Map<String, Object> list() {
        List<Map<String, String>> list = new ArrayList<>();
        for (AiModelPO m : modelService.listModels()) {
            list.add(Map.of(
                    "key", m.getModelKey(),
                    "label", m.getLabel(),
                    "modelName", m.getModelName(),
                    "baseUrl", m.getBaseUrl() != null ? m.getBaseUrl() : "",
                    "apiKeyMasked", maskKey(m.getApiKey()),
                    "isDefault", String.valueOf(Boolean.TRUE.equals(m.getIsDefault()))
            ));
        }
        return Map.of("success", true, "models", list);
    }

    @PostMapping
    public Map<String, Object> add(@RequestBody Map<String, String> body) {
        String key = body.get("key");
        String label = body.get("label");
        String apiKey = body.get("apiKey");
        String baseUrl = body.get("baseUrl");
        String modelName = body.get("modelName");
        if (key == null || label == null || apiKey == null || baseUrl == null || modelName == null) {
            return Map.of("success", false, "error", "所有字段必填");
        }
        modelService.addModel(key, label, apiKey, baseUrl, modelName);
        return Map.of("success", true, "added", key);
    }

    @PutMapping("/{key}")
    public Map<String, Object> update(@PathVariable String key, @RequestBody Map<String, String> body) {
        String label = nullIfBlank(body.get("label"));
        String apiKey = nullIfBlank(body.get("apiKey"));
        String baseUrl = nullIfBlank(body.get("baseUrl"));
        String modelName = nullIfBlank(body.get("modelName"));
        if (label == null && apiKey == null && baseUrl == null && modelName == null) {
            return Map.of("success", false, "error", "没有需要更新的字段");
        }
        boolean updated = modelService.updateModel(key, label, apiKey, baseUrl, modelName);
        if (!updated) {
            return Map.of("success", false, "error", "模型不存在: " + key);
        }
        return Map.of("success", true, "updated", key);
    }

    @DeleteMapping("/{key}")
    public Map<String, Object> delete(@PathVariable String key) {
        modelService.deleteModel(key);
        return Map.of("success", true, "deleted", key);
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String maskKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return "";
        String s = apiKey.trim();
        if (s.length() <= 8) return "****";
        return s.substring(0, 4) + "****" + s.substring(s.length() - 4);
    }
}
