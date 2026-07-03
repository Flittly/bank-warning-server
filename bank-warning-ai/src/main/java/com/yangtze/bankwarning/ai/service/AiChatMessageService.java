package com.yangtze.bankwarning.ai.service;

import com.yangtze.bankwarning.ai.domain.po.AiChatMessagePO;
import com.yangtze.bankwarning.ai.mapper.AiChatMessageMapper;
import com.yangtze.bankwarning.security.security.SecurityUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

@Service
public class AiChatMessageService {

    private final AiChatMessageMapper mapper;

    public AiChatMessageService(AiChatMessageMapper mapper) {
        this.mapper = mapper;
    }

    public List<Map<String, Object>> listMessages(String sessionId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        return mapper.selectBySessionId(sessionId, userId).stream()
                .map(m -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("role", m.getRole());
                    map.put("content", m.getContent());
                    if (m.getContextText() != null) {
                        map.put("contextText", m.getContextText());
                    }
                    return map;
                })
                .collect(Collectors.toList());
    }

    public void saveMessage(String sessionId, String role, String content, String contextText) {
        AiChatMessagePO po = new AiChatMessagePO();
        po.setSessionId(sessionId);
        po.setRole(role);
        po.setContent(content);
        po.setContextText(contextText);
        po.setUserId(SecurityUtils.getCurrentUserId());
        mapper.insert(po);
    }

    public void deleteBySessionId(String sessionId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        mapper.deleteBySessionId(sessionId, userId);
    }
}
