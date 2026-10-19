package com.yangtze.bankwarning.mapper;

import com.yangtze.bankwarning.domain.po.BasicParamPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface BasicParamMapper {

    /**
     * 模板库列表。**不含任务参数**（task_id 非空的行）——
     * 资源树的「参数模板」文件夹展示的是可复用主数据，混进 N 个任务的私有副本没有意义。
     */
    List<BasicParamPO> selectAll(@Param("userId") Long userId);

    BasicParamPO selectByParamId(@Param("paramId") String paramId, @Param("userId") Long userId);

    BasicParamPO selectById(@Param("id") Integer id, @Param("userId") Long userId);

    /** 取某任务的参数集（未软删）。没有则返回 null，由 Service 触发物化。 */
    BasicParamPO selectByTaskId(@Param("taskId") String taskId, @Param("userId") Long userId);

    /** 取某任务的参数集，**包含已软删的行**。物化时用来「复活」而不是重复插入。 */
    BasicParamPO selectByTaskIdIncludingDeleted(@Param("taskId") String taskId, @Param("userId") Long userId);

    int insert(BasicParamPO param);

    int update(BasicParamPO param);

    /** 任务删除（软删）时一并软删它的参数集，避免孤儿行占住 uq_basic_params_task_id。 */
    int softDeleteByTaskId(@Param("taskId") String taskId, @Param("userId") Long userId);

    /** 物化时复活一条曾被软删的任务参数集。 */
    int reviveByTaskId(@Param("taskId") String taskId, @Param("userId") Long userId);
}
