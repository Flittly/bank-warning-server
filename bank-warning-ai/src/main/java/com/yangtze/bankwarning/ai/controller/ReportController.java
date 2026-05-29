package com.yangtze.bankwarning.ai.controller;

import com.yangtze.bankwarning.ai.service.ReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/v0/bank/ai")
public class ReportController {

    private static final Logger log = LoggerFactory.getLogger(ReportController.class);
    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @PostMapping("/reports/{section_id}")
    public Map<String, Object> generateReport(@PathVariable("section_id") String sectionId) {
        log.info("[api] report for section={}", sectionId);
        String report = reportService.generateReport(sectionId);
        return Map.of("success", true, "section_id", sectionId, "report", report);
    }

    @PostMapping("/reports/task/{task_id}")
    public Map<String, Object> generateTaskReports(@PathVariable("task_id") String taskId) {
        log.info("[api] reports for task={}", taskId);
        Map<String, String> reports = reportService.generateTaskReports(taskId);
        return Map.of("success", true, "task_id", taskId, "reports", reports);
    }
}
