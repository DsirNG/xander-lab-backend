package com.xander.lab.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * FlowCraft 画布实体
 */
@Data
@TableName("flow_canvas")
public class FlowCanvas {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属项目ID */
    private Long projectId;

    /** 画布名称 */
    private String name;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
