package com.yangtze.bankwarning.ai.controller;

import com.yangtze.bankwarning.ai.service.RagTraceQueryService;
import com.yangtze.bankwarning.security.security.SecurityUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阶段 5：检索留痕的查询端点。
 *
 * <p>单独一个 Controller 而不是塞进 {@code KnowledgeController}：后者已经背着入库、切分、
 * 上传、统计四件事（600+ 行），而这里只读、只查一张旁路表，混进去会让两件不相干的事
 * 共享同一个构造器和同一批测试参数。
 *
 * <p><b>为什么值得开这个口子</b>：阶段 5 之前 {@code ai_rag_traces} 一直是 0 行，
 * 而「0 行」既可能是表没建、也可能是开关关了、还可能是没人提问——三者要做的事完全不同。
 * 有了这两个端点，「合闸之后空返回率有没有变、精排把 p95 拉高多少」才能用一条 curl 回答，
 * 而不是靠人肉回忆。
 *
 * <p>权限口径沿用全项目的数据范围：{@code getCurrentUserIdForDataFilter()} 返回 null 即
 * 管理员（看全部），否则只看自己 userId 的行。
 */
@RestController
@RequestMapping("/v0/bank/ai/traces")
public class RagTraceController {

    private final RagTraceQueryService queryService;

    public RagTraceController(RagTraceQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * 最近的留痕，新的在前。
     *
     * @param limit     1..200，默认 50
     * @param hours     回看窗口（小时），默认 24
     * @param scenario  CHAT / TOOL / SEARCH / REPORT
     * @param status    OK / UNEXPECTED / 具体降级原因
     * @param onlyEmpty true 时只看「成功但一条都没返回」的行
     */
    @GetMapping
    public Map<String, Object> list(
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            @RequestParam(name = "hours", defaultValue = "24") int hours,
            @RequestParam(name = "scenario", required = false) String scenario,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "onlyEmpty", defaultValue = "false") boolean onlyEmpty) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        List<Map<String, Object>> items =
                queryService.recent(userId, scenario, status, onlyEmpty, limit, hours);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("count", items.size());
        body.put("windowHours", hours);
        body.put("onlyEmpty", onlyEmpty);
        return body;
    }

    /**
     * 按天聚合 + 空返回原因分布。
     *
     * <p>两个数最常用：{@code emptyCalls} 回答「多少提问没查到东西」，
     * {@code p95Ms} 回答「开了精排之后慢了多少」。
     */
    @GetMapping("/summary")
    public Map<String, Object> summary(@RequestParam(name = "days", defaultValue = "7") int days) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("daily", queryService.daily(userId, days));
        body.put("emptyReasons", queryService.emptyReasons(userId, days));
        body.put("windowDays", days);
        return body;
    }
}
