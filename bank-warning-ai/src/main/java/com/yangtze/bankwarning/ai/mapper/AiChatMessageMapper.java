package com.yangtze.bankwarning.ai.mapper;

import com.yangtze.bankwarning.ai.domain.po.AiChatMessagePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AiChatMessageMapper {
    List<AiChatMessagePO> selectBySessionId(@Param("sessionId") String sessionId, @Param("userId") Long userId);
    int insert(AiChatMessagePO po);
    int deleteBySessionId(@Param("sessionId") String sessionId, @Param("userId") Long userId);
}
