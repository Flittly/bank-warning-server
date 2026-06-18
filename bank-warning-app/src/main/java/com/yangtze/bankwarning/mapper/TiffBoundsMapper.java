package com.yangtze.bankwarning.mapper;

import com.yangtze.bankwarning.domain.po.TiffBoundsPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TiffBoundsMapper {

    List<TiffBoundsPO> selectAll(@Param("userId") Long userId);

    TiffBoundsPO selectByTiffKey(@Param("tiffKey") String tiffKey, @Param("userId") Long userId);

    int insertOrUpdate(TiffBoundsPO tiffBounds, @Param("userId") Long userId);
}
