package com.yangtze.bankwarning.ai.service;

import com.yangtze.bankwarning.ai.domain.po.AiChatMessagePO;
import com.yangtze.bankwarning.ai.mapper.AiChatMessageMapper;
import com.yangtze.bankwarning.ai.mapper.AiChatSessionMapper;
import com.yangtze.bankwarning.security.security.SecurityUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

@Service
public class AiChatMessageService {

    private final AiChatMessageMapper mapper;
    /** 只为了在写消息后刷新会话的 updated_at（列表按它排「最近」）。 */
    private final AiChatSessionMapper sessionMapper;

    public AiChatMessageService(AiChatMessageMapper mapper, AiChatSessionMapper sessionMapper) {
        this.mapper = mapper;
        this.sessionMapper = sessionMapper;
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
        // 会话行的 updated_at 不会随消息自动变；不手动摸一下，「最近会话」永远按创建时间排
        sessionMapper.touch(sessionId);
    }

    public void deleteBySessionId(String sessionId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        mapper.deleteBySessionId(sessionId, userId);
    }
}
