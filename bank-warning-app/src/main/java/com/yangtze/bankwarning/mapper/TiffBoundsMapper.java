package com.yangtze.bankwarning.mapper;

import com.yangtze.bankwarning.domain.po.TiffBoundsPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TiffBoundsMapper {

    List<TiffBoundsPO> selectAll(@Param("userId") Long userId);

    /** 按 tiff_key 取单行，**不做用户过滤**（权限由调用方判定）。 */
    TiffBoundsPO selectByTiffKey(@Param("tiffKey") String tiffKey);

}
