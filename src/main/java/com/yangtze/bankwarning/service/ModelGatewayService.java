package com.yangtze.bankwarning.service;

import com.yangtze.bankwarning.config.ModelServiceProperties;
import com.yangtze.bankwarning.dto.ModelPredictRequest;
import com.yangtze.bankwarning.model.ParameterProfile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ModelGatewayService {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE = new ParameterizedTypeReference<>() {
    };

    private final WebClient modelWebClient;
    private final ModelServiceProperties properties;
    private final ModelParameterService parameterService;

    public ModelGatewayService(
            WebClient modelWebClient,
            ModelServiceProperties properties,
            ModelParameterService parameterService) {
        this.modelWebClient = modelWebClient;
        this.properties = properties;
        this.parameterService = parameterService;
    }

    public Map<String, Object> health() {
        return modelWebClient.get()
                .uri("/api/v1/health")
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .block(properties.getConnectTimeout());
    }

    public Map<String, Object> predict(ModelPredictRequest request) {
        String modelApi = normalizeModelApi(request.modelApi());
        Map<String, Object> effectiveParameters = parameterService.resolve(modelApi, request.profileNames());
        if (request.payload() != null) {
            effectiveParameters.putAll(request.payload());
        }

        Map<String, Object> rawResult = modelWebClient.post()
                .uri("/api/v1/predict")
                .bodyValue(Map.of(
                        "model_api", modelApi,
                        "payload", effectiveParameters,
                        "timeout_seconds", request.timeoutSeconds()))
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .block(resolveTimeout(request.timeoutSeconds()));

        return Map.of(
                "modelApi", modelApi,
                "effectiveParameters", effectiveParameters,
                "rawResult", rawResult,
                "formattedResult", formatResult(rawResult));
    }

    public Map<String, Object> runLegacyModelAndWait(
            String modelApi,
            Map<String, Object> payload,
            Integer timeoutSeconds) {
        String normalizedModelApi = normalizeModelApi(modelApi);
        Map<String, Object> submitResult = modelWebClient.post()
                .uri(normalizedModelApi)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .block(resolveTimeout(timeoutSeconds));

        Object caseId = submitResult.get("case-id");
        if (caseId == null) {
            throw new IllegalStateException("Python model service did not return case-id");
        }

        Duration timeout = resolveTimeout(timeoutSeconds);
        long deadline = System.currentTimeMillis() + timeout.toMillis();

        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> status = modelWebClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/v0/mc/status").queryParam("case_id", caseId).build())
                    .retrieve()
                    .bodyToMono(MAP_TYPE)
                    .block(properties.getConnectTimeout());

            String statusValue = String.valueOf(status.get("status"));
            if ("completed".equalsIgnoreCase(statusValue) || "COMPLETE".equalsIgnoreCase(statusValue)) {
                Map<String, Object> result = modelWebClient.get()
                        .uri(uriBuilder -> uriBuilder.path("/v0/mc/result").queryParam("case_id", caseId).build())
                        .retrieve()
                        .bodyToMono(MAP_TYPE)
                        .block(properties.getConnectTimeout());
                Object payloadResult = result.get("result");
                if (payloadResult instanceof Map<?, ?> map) {
                    return castMap(map);
                }
                return new LinkedHashMap<>(submitResult);
            }
            if ("error".equalsIgnoreCase(statusValue) || "ERROR".equalsIgnoreCase(statusValue)) {
                Map<String, Object> error = modelWebClient.get()
                        .uri(uriBuilder -> uriBuilder.path("/v0/mc/error").queryParam("case_id", caseId).build())
                        .retrieve()
                        .bodyToMono(MAP_TYPE)
                        .block(properties.getConnectTimeout());
                throw new IllegalStateException(String.valueOf(error.get("error")));
            }

            try {
                Thread.sleep(1000L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for model result", exception);
            }
        }

        throw new IllegalStateException("Model execution timed out");
    }

    public List<ParameterProfile> listProfiles() {
        return parameterService.list();
    }

    private Map<String, Object> castMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private Map<String, Object> formatResult(Map<String, Object> rawResult) {
        Map<String, Object> formatted = new LinkedHashMap<>();
        Object riskLevel = rawResult.get("risk-level");
        formatted.put("caseId", rawResult.get("case-id"));
        formatted.put("score", rawResult.get("result"));
        formatted.put("riskLevel", riskLevel);
        formatted.put("riskLabel", toRiskLabel(riskLevel));
        formatted.put("indicatorCaseIds", rawResult.get("multi-indicator-ids"));
        return formatted;
    }

    private String toRiskLabel(Object riskLevel) {
        if (riskLevel instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) {
                Object item = list.get(index);
                if (item instanceof Number number && number.intValue() == 1) {
                    return "LEVEL_" + (index + 1);
                }
            }
        }
        return riskLevel == null ? "UNKNOWN" : String.valueOf(riskLevel);
    }

    private String normalizeModelApi(String modelApi) {
        if (modelApi == null || modelApi.isBlank()) {
            return properties.getDefaultModelApi();
        }
        return modelApi;
    }

    private Duration resolveTimeout(Integer timeoutSeconds) {
        return timeoutSeconds == null ? properties.getReadTimeout() : Duration.ofSeconds(timeoutSeconds.longValue());
    }
}
