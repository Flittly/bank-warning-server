package com.yangtze.bankwarning.ai.hook;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PreActingEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class ReasoningTraceHook implements Hook {

    private final List<ThoughtLogEntry> log = new CopyOnWriteArrayList<>();

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreActingEvent preActingEvent) {
            Agent agent = preActingEvent.getAgent();
            String agentName = agent != null ? agent.getName() : "unknown";
            String toolName = preActingEvent.getToolUse() != null ? preActingEvent.getToolUse().getName() : "";
            String input = preActingEvent.getToolUse() != null
                    ? String.valueOf(preActingEvent.getToolUse().getInput())
                    : "";
            log.add(new ThoughtLogEntry(LocalDateTime.now(), agentName, "action",
                    toolName + "(" + input + ")"));
        } else if (event instanceof PostActingEvent postActingEvent) {
            Agent agent = postActingEvent.getAgent();
            String agentName = agent != null ? agent.getName() : "unknown";
            String result = postActingEvent.getToolResult() != null
                    ? String.valueOf(postActingEvent.getToolResult().getOutput())
                    : "";
            log.add(new ThoughtLogEntry(LocalDateTime.now(), agentName, "result", result));
        }
        return Mono.just(event);
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
