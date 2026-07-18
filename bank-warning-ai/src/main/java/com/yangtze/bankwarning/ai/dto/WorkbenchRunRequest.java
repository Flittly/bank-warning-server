package com.yangtze.bankwarning.ai.dto;

import java.util.List;

public record WorkbenchRunRequest(
    String prompt,
    String sessionId,
    List<AgentSpec> agents,
    List<ItemRef> tasks,
    List<ItemRef> reports,
    List<Connection> connections
) {
    public record AgentSpec(String id, String name, String role, String desc, String color, List<String> skills) {}
    public record ItemRef(String id, String name) {}
    public record Connection(String taskId, String reportId, String agentId) {}
}
