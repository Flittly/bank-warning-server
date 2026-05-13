package com.yangtze.bankwarning.mapper;

import com.yangtze.bankwarning.domain.po.TiffBoundsPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TiffBoundsMapper {

    List<TiffBoundsPO> selectAll();

    TiffBoundsPO selectByTiffKey(@Param("tiffKey") String tiffKey);

    int insertOrUpdate(TiffBoundsPO tiffBounds);
}
