package com.yangtze.bankwarning.controller;

import com.yangtze.bankwarning.service.GlobalSearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 全局搜索接口。挂在 /v0/bank 下与业务接口同源（鉴权拦截器按前缀生效）。
 */
@RestController
@RequestMapping("/v0/bank")
public class SearchController {

    private final GlobalSearchService globalSearchService;

    public SearchController(GlobalSearchService globalSearchService) {
        this.globalSearchService = globalSearchService;
    }

    /**
     * GET /v0/bank/search?q=张家港&amp;limit=50
     *
     * <p>q 支持混合语法：{@code risk:3 断面}、{@code type:岸段 线段}、{@code task:task-1792030029572 冲刷}。
     */
    @GetMapping("/search")
    public Map<String, Object> search(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return globalSearchService.search(q, limit);
    }
}
