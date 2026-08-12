package com.yangtze.bankwarning.ai.controller;

import com.yangtze.bankwarning.ai.dto.WorkbenchRunRequest;
import com.yangtze.bankwarning.ai.workflow.WorkbenchOrchestratorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/v0/bank/ai/workbench")
public class WorkbenchController {

    private static final Logger log = LoggerFactory.getLogger(WorkbenchController.class);

    private final WorkbenchOrchestratorService orchestratorService;

    public WorkbenchController(WorkbenchOrchestratorService orchestratorService) {
        this.orchestratorService = orchestratorService;
    }

    @GetMapping("/configs")
    public Object listConfigs() {
        return orchestratorService.listConfigs();
    }

    @GetMapping("/configs/{id}")
    public Object getConfig(@PathVariable Long id) {
        return orchestratorService.getConfig(id);
    }

    @DeleteMapping("/configs/{id}")
    public Map<String, Object> deleteConfig(@PathVariable Long id) {
        return orchestratorService.deleteConfig(id);
    }

    @PostMapping("/save")
    public Map<String, Object> save(@RequestBody WorkbenchRunRequest request) {
        log.info("[workbench] save: agents={}, tasks={}", request.agents().size(), request.tasks().size());
        return orchestratorService.save(request);
    }

    @PostMapping("/run")
    public Map<String, Object> run(@RequestBody WorkbenchRunRequest request) {
        log.info("[workbench] run: agents={}, tasks={}", request.agents().size(), request.tasks().size());
        return orchestratorService.run(request);
    }

    @PostMapping("/run-latest")
    public Map<String, Object> runLatest() {
        log.info("[workbench] run-latest");
        return orchestratorService.runLatest();
    }
}
