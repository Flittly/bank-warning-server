package com.yangtze.bankwarning.service;

import com.yangtze.bankwarning.config.ModelServiceProperties;
import com.yangtze.bankwarning.domain.dto.ModelPredictRequest;
import com.yangtze.bankwarning.model.ParameterProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ModelGatewayService {

    private static final Logger log = LoggerFactory.getLogger(ModelGatewayService.class);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE = new ParameterizedTypeReference<Map<String, Object>>() {
    };

    private final RestClient modelRestClient;
    private final ModelServiceProperties properties;
    private final ModelParameterService parameterService;

    public ModelGatewayService(
            RestClient modelRestClient,
            ModelServiceProperties properties,
            ModelParameterService parameterService) {
        this.modelRestClient = modelRestClient;
        this.properties = properties;
        this.parameterService = parameterService;
    }

    public Map<String, Object> health() {
        return modelRestClient.get()
                .uri("/api/v1/health")
                .retrieve()
                .body(MAP_TYPE);
    }

    public Map<String, Object> predict(ModelPredictRequest request) {
        String modelApi = normalizeModelApi(request.modelApi());
        Map<String, Object> effectiveParameters = parameterService.resolve(modelApi, request.profileNames());
        if (request.payload() != null) {
            effectiveParameters.putAll(request.payload());
        }

        Map<String, Object> rawResult = modelRestClient.post()
                .uri("/api/v1/predict")
                .body(Map.of(
                        "model_api", modelApi,
                        "payload", effectiveParameters,
                        "timeout_seconds", request.timeoutSeconds()))
                .retrieve()
                .body(MAP_TYPE);

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
        return waitForLegacyModelResult(submitLegacyModel(modelApi, payload, timeoutSeconds), timeoutSeconds);
    }

    public Map<String, Object> runLegacyModelAndWaitForCase(
            String modelApi,
            Map<String, Object> payload,
            Integer timeoutSeconds) {
        Map<String, Object> submitResult = submitLegacyModel(modelApi, payload, timeoutSeconds);
        Object caseId = submitResult.get("case-id");
        Map<String, Object> result = waitForLegacyModelResult(submitResult, timeoutSeconds);
        return Map.of(
                "caseId", String.valueOf(caseId),
                "result", result);
    }

    public String fetchModelCaseFile(String caseId, String filename) {
        return modelRestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v0/fs/result/file")
                        .queryParam("case_id", caseId)
                        .queryParam("filename", filename)
                        .build())
                .accept(MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM, MediaType.APPLICATION_JSON)
                .retrieve()
                .body(String.class);
    }

    private Map<String, Object> submitLegacyModel(
            String modelApi,
            Map<String, Object> payload,
            Integer timeoutSeconds) {
        String normalizedModelApi = normalizeModelApi(modelApi);
        return modelRestClient.post()
                .uri(normalizedModelApi)
                .body(payload)
                .retrieve()
                .body(MAP_TYPE);
    }

    private Map<String, Object> waitForLegacyModelResult(
            Map<String, Object> submitResult,
            Integer timeoutSeconds) {
        Object caseId = submitResult.get("case-id");
        if (caseId == null) {
            throw new IllegalStateException("Python model service did not return case-id");
        }

        Duration timeout = resolveTimeout(timeoutSeconds);
        long deadline = System.currentTimeMillis() + timeout.toMillis();

        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> status = modelRestClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/v0/mc/status").queryParam("case_id", caseId).build())
                    .retrieve()
                    .body(MAP_TYPE);

            String statusValue = String.valueOf(status.get("status"));
            if ("completed".equalsIgnoreCase(statusValue) || "COMPLETE".equalsIgnoreCase(statusValue)) {
                Map<String, Object> result = modelRestClient.get()
                        .uri(uriBuilder -> uriBuilder.path("/v0/mc/result").queryParam("case_id", caseId).build())
                        .retrieve()
                        .body(MAP_TYPE);
                Object payloadResult = result.get("result");
                if (payloadResult instanceof Map<?, ?> map) {
                    return castMap(map);
                }
                return new LinkedHashMap<>(submitResult);
            }
            if ("error".equalsIgnoreCase(statusValue) || "ERROR".equalsIgnoreCase(statusValue)) {
                Map<String, Object> error = modelRestClient.get()
                        .uri(uriBuilder -> uriBuilder.path("/v0/mc/error").queryParam("case_id", caseId).build())
                        .retrieve()
                        .body(MAP_TYPE);
                log.error("[model-error] received error from model service, caseId={}, errorResponse={}", caseId, error);
                throw new IllegalStateException(buildDetailedModelError(error));
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

    private String buildDetailedModelError(Map<String, Object> errorResponse) {
        Object errorMessage = errorResponse.get("error");
        String base = errorMessage == null ? "" : String.valueOf(errorMessage).trim();

        Object runtimeObj = errorResponse.get("runtime");
        if (runtimeObj instanceof Map<?, ?> runtimeMap) {
            Object runtimeMessage = runtimeMap.get("message");
            if (runtimeMessage instanceof String runtimeText && !runtimeText.isBlank()) {
                if (base.isBlank() || "OK".equalsIgnoreCase(base)) {
                    base = runtimeText;
                } else if (!base.contains(runtimeText)) {
                    base = base + " | runtime=" + runtimeText;
                }
            }
        }

        Object eventsObj = errorResponse.get("events");
        if (eventsObj instanceof List<?> events) {
            for (int index = events.size() - 1; index >= 0; index--) {
                Object eventObj = events.get(index);
                if (!(eventObj instanceof Map<?, ?> eventMap)) {
                    continue;
                }
                Object level = eventMap.get("level");
                if (!(level instanceof String levelText) || !"error".equalsIgnoreCase(levelText)) {
                    continue;
                }
                Object message = eventMap.get("message");
                if (message instanceof String messageText && !messageText.isBlank() && !base.contains(messageText)) {
                    base = (base.isBlank() ? messageText : base + " | event=" + messageText);
                    break;
                }
            }
        }

        if (base.isBlank() || "OK".equalsIgnoreCase(base)) {
            return "Model service returned error status but no valid error message. Full response: " + errorResponse;
        }
        return base;
    }
}
