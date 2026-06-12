package com.yangtze.bankwarning.controller;

import com.yangtze.bankwarning.service.AiChatSessionService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/v0/bank/ai/chat")
public class AiChatController {

    private final AiChatSessionService sessionService;

    public AiChatController(AiChatSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @GetMapping("/sessions")
    public Map<String, Object> listSessions() {
        var sessions = sessionService.listSessions().stream()
                .map(s -> Map.of(
                        "session_id", s.getSessionId(),
                        "title", s.getTitle(),
                        "created_at", s.getCreatedAt(),
                        "updated_at", s.getUpdatedAt()
                ))
                .collect(Collectors.toList());
        return Map.of("success", true, "sessions", sessions);
    }

    @PostMapping("/sessions")
    public Map<String, Object> createSession(@RequestBody Map<String, String> body) {
        var session = sessionService.createSession(body.get("title"));
        return Map.of("success", true, "session_id", session.getSessionId(),
                "title", session.getTitle());
    }

    @DeleteMapping("/sessions/{session_id}")
    public Map<String, Object> deleteSession(@PathVariable("session_id") String sessionId) {
        sessionService.deleteSession(sessionId);
        return Map.of("success", true, "session_id", sessionId, "deleted", true);
    }
}
