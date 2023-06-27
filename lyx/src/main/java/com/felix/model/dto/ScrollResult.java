package com.felix.model.dto;

import lombok.Data;

import java.util.List;

/**
 * 分页封装数据类
 */
@Data
public class ScrollResult {
    private List<?> list;
    private Long minTime;
    private Integer offset;
}
