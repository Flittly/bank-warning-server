package com.yangtze.bankwarning.mapper;

import com.yangtze.bankwarning.domain.po.AiChatMessagePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AiChatMessageMapper {

    /** 按会话 ID 获取全部消息（按时间升序） */
    List<AiChatMessagePO> selectBySessionId(@Param("sessionId") String sessionId, @Param("userId") Long userId);

    /** 插入一条消息 */
    int insert(AiChatMessagePO po);

    /** 删除会话下全部消息 */
    int deleteBySessionId(@Param("sessionId") String sessionId, @Param("userId") Long userId);
}
