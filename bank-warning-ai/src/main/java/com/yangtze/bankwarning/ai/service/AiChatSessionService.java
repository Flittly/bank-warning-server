package com.yangtze.bankwarning.ai.service;

import com.yangtze.bankwarning.ai.domain.po.AiChatSessionPO;
import com.yangtze.bankwarning.ai.mapper.AiChatSessionMapper;
import com.yangtze.bankwarning.security.security.SecurityUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class AiChatSessionService {

    private final AiChatSessionMapper mapper;

    public AiChatSessionService(AiChatSessionMapper mapper) {
        this.mapper = mapper;
    }

    public List<AiChatSessionPO> listSessions() {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        return mapper.selectAll(userId);
    }

    public AiChatSessionPO createSession(String title) {
        AiChatSessionPO po = new AiChatSessionPO();
        String uuid = UUID.randomUUID().toString();
        po.setSessionId(uuid);
        po.setTitle(title != null && !title.isBlank() ? title : "会话-" + uuid.substring(0, 8));
        po.setUserId(SecurityUtils.getCurrentUserId());
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        mapper.insert(po);
        return po;
    }

    public void deleteSession(String sessionId) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        mapper.deleteBySessionId(sessionId, userId);
    }

    public void updateTitle(String sessionId, String title) {
        Long userId = SecurityUtils.getCurrentUserIdForDataFilter();
        mapper.updateTitle(sessionId, title, userId);
    }
}
