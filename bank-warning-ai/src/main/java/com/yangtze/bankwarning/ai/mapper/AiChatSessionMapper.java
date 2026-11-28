package com.yangtze.bankwarning.ai.mapper;

import com.yangtze.bankwarning.ai.domain.po.AiChatSessionPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AiChatSessionMapper {
    List<AiChatSessionPO> selectAll(@Param("userId") Long userId);
    AiChatSessionPO selectBySessionId(@Param("sessionId") String sessionId, @Param("userId") Long userId);
    int insert(AiChatSessionPO po);
    int deleteBySessionId(@Param("sessionId") String sessionId, @Param("userId") Long userId);

    /**
     * 刷新会话的 {@code updated_at}。
     *
     * <p>会话列表按 {@code updated_at DESC} 排「最近会话」，而插消息只写 {@code ai_chat_messages}、
     * 不碰会话行 —— 不打这一下，列表顺序会永远停在「创建时间」，刚聊过的会话沉到底部。
     */
    int touch(@Param("sessionId") String sessionId);
}
