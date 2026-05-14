package com.yangtze.bankwarning.controller;

import com.yangtze.bankwarning.domain.dto.ModelParameterUpdateRequest;
import com.yangtze.bankwarning.domain.dto.ModelPredictRequest;
import com.yangtze.bankwarning.service.ModelGatewayService;
import com.yangtze.bankwarning.service.ModelParameterService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/model")
public class ModelController {

    private final ModelGatewayService modelGatewayService;
    private final ModelParameterService modelParameterService;

    public ModelController(ModelGatewayService modelGatewayService, ModelParameterService modelParameterService) {
        this.modelGatewayService = modelGatewayService;
        this.modelParameterService = modelParameterService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ok");
        response.put("pythonModelService", modelGatewayService.health());
        return response;
    }

    @GetMapping("/parameters")
    public Map<String, Object> listProfiles() {
        return Map.of("profiles", modelGatewayService.listProfiles());
    }

    @PostMapping("/parameters")
    public Map<String, Object> updateParameters(@Valid @RequestBody ModelParameterUpdateRequest request) {
        return Map.of("profile", modelParameterService.update(request));
    }

    @PostMapping("/predict")
    public Map<String, Object> predict(@Valid @RequestBody ModelPredictRequest request) {
        return modelGatewayService.predict(request);
    }
}
