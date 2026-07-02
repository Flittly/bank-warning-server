package com.yangtze.bankwarning.service;

import com.yangtze.bankwarning.domain.po.AiChatMessagePO;
import com.yangtze.bankwarning.mapper.AiChatMessageMapper;
import com.yangtze.bankwarning.security.security.SecurityUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;
import java.util.Map;

@Service
public class AiChatMessageService {

    private final AiChatMessageMapper mapper;

    public AiChatMessageService(AiChatMessageMapper mapper) {
        this.mapper = mapper;
    }

    /** 获取会话的全部消息（按时间升序），转为前端友好的 {role, content, contextText} 格式 */
    public List<Map<String, Object>> listMessages(String sessionId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        return mapper.selectBySessionId(sessionId, userId).stream()
                .map(m -> {
                    Map<String, Object> map = new java.util.LinkedHashMap<>();
                    map.put("role", m.getRole());
                    map.put("content", m.getContent());
                    if (m.getContextText() != null) {
                        map.put("contextText", m.getContextText());
                    }
                    return map;
                })
                .collect(Collectors.toList());
    }

    /** 保存一条消息 */
    public void saveMessage(String sessionId, String role, String content, String contextText) {
        AiChatMessagePO po = new AiChatMessagePO();
        po.setSessionId(sessionId);
        po.setRole(role);
        po.setContent(content);
        po.setContextText(contextText);
        po.setUserId(SecurityUtils.getCurrentUserId());
        mapper.insert(po);
    }

    /** 删除会话下全部消息（删除会话时级联） */
    public void deleteBySessionId(String sessionId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        mapper.deleteBySessionId(sessionId, userId);
    }
}
