package com.xander.lab.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * FlowCraft 项目实体
 */
@Data
@TableName("flow_project")
public class FlowProject {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属用户ID */
    private Long userId;

    /** 项目名称 */
    private String name;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
