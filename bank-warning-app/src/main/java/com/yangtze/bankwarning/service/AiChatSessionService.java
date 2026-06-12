package com.yangtze.bankwarning.service;

import com.yangtze.bankwarning.domain.po.AiChatSessionPO;
import com.yangtze.bankwarning.mapper.AiChatSessionMapper;
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
        return mapper.selectAll();
    }

    public AiChatSessionPO createSession(String title) {
        AiChatSessionPO po = new AiChatSessionPO();
        po.setSessionId(UUID.randomUUID().toString());
        po.setTitle(title != null && !title.isBlank() ? title : "新会话");
        mapper.insert(po);
        return po;
    }

    public void deleteSession(String sessionId) {
        mapper.deleteBySessionId(sessionId);
    }

    public void updateTitle(String sessionId, String title) {
        mapper.updateTitle(sessionId, title);
    }
}
