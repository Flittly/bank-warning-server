package com.yangtze.bankwarning.controller;

import com.yangtze.bankwarning.service.AiChatMessageService;
import com.yangtze.bankwarning.service.AiChatSessionService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/v0/bank/ai/chat")
public class AiChatController {

    private final AiChatSessionService sessionService;
    private final AiChatMessageService messageService;

    public AiChatController(AiChatSessionService sessionService, AiChatMessageService messageService) {
        this.sessionService = sessionService;
        this.messageService = messageService;
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
        messageService.deleteBySessionId(sessionId); // 级联删除消息
        sessionService.deleteSession(sessionId);
        return Map.of("success", true, "session_id", sessionId, "deleted", true);
    }

    @GetMapping("/sessions/{session_id}/messages")
    public Map<String, Object> listMessages(@PathVariable("session_id") String sessionId) {
        List<Map<String, Object>> messages = messageService.listMessages(sessionId);
        return Map.of("success", true, "messages", messages);
    }

    @PostMapping("/sessions/{session_id}/messages")
    public Map<String, Object> saveMessage(@PathVariable("session_id") String sessionId,
                                           @RequestBody Map<String, String> body) {
        String role = body.get("role");
        String content = body.get("content");
        String contextText = body.get("contextText");
        messageService.saveMessage(sessionId, role, content, contextText);
        return Map.of("success", true);
    }
}
