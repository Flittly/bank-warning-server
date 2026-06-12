package com.yangtze.bankwarning.ai.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;

import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

public class ReasoningTraceMiddleware implements MiddlewareBase {

    private final List<ThoughtLogEntry> log = new CopyOnWriteArrayList<>();

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        String agentName = agent != null ? agent.getName() : "unknown";

        for (ToolUseBlock toolUse : input.toolCalls()) {
            log.add(new ThoughtLogEntry(LocalDateTime.now(), agentName, "action",
                    toolUse.getName() + "(" + toolUse.getInput() + ")"));
        }

        Map<String, StringBuilder> resultBuffers = new HashMap<>();

        return next.apply(input)
                .doOnNext(event -> {
                    if (event instanceof ToolResultTextDeltaEvent delta) {
                        resultBuffers.computeIfAbsent(delta.getToolCallId(), k -> new StringBuilder())
                                .append(delta.getDelta());
                    } else if (event instanceof ToolResultEndEvent end) {
                        StringBuilder buf = resultBuffers.remove(end.getToolCallId());
                        String result = buf != null ? buf.toString() : "";
                        log.add(new ThoughtLogEntry(LocalDateTime.now(), agentName, "result", result));
                    }
                });
    }

    public List<ThoughtLogEntry> getLog() {
        return Collections.unmodifiableList(new ArrayList<>(log));
    }

    public void clearLog() {
        log.clear();
    }

    public static class ThoughtLogEntry {
        private LocalDateTime timestamp;
        private String agentName;
        private String type;
        private String content;

        public ThoughtLogEntry() {
        }

        public ThoughtLogEntry(LocalDateTime timestamp, String agentName, String type, String content) {
            this.timestamp = timestamp;
            this.agentName = agentName;
            this.type = type;
            this.content = content;
        }

        public LocalDateTime getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(LocalDateTime timestamp) {
            this.timestamp = timestamp;
        }

        public String getAgentName() {
            return agentName;
        }

        public void setAgentName(String agentName) {
            this.agentName = agentName;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }
    }
}
