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
    int updateTitle(@Param("sessionId") String sessionId, @Param("title") String title, @Param("userId") Long userId);
}
