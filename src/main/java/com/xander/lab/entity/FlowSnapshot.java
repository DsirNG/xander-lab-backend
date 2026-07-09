package com.xander.lab.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * FlowCraft 画布快照实体
 * 每个画布最多一条快照记录（UNIQUE canvas_id）
 */
@Data
@TableName("flow_snapshot")
public class FlowSnapshot {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属画布ID */
    private Long canvasId;

    /** 快照JSON数据 */
    private String data;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
