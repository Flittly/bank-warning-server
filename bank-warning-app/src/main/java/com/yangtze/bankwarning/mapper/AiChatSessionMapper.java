package com.yangtze.bankwarning.mapper;

import com.yangtze.bankwarning.domain.po.AiChatSessionPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface AiChatSessionMapper {
    List<AiChatSessionPO> selectAll();
    AiChatSessionPO selectBySessionId(@Param("sessionId") String sessionId);
    int insert(AiChatSessionPO po);
    int deleteBySessionId(@Param("sessionId") String sessionId);
    int updateTitle(@Param("sessionId") String sessionId, @Param("title") String title);
}
