package com.xander.lab.dto.flow;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 项目列表/详情视图对象
 */
@Data
public class FlowProjectVO {
    private Long id;
    private String name;
    private LocalDateTime updatedAt;

    /** 详情模式下返回的画布列表 */
    private List<FlowCanvasVO> canvases;

    private LocalDateTime createdAt;
}
