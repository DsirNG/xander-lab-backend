package com.xander.lab.agent.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** One immutable, user-owned Skill instruction version. */
@Data
@TableName("agent_skill")
public class AgentUserSkill {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String skillKey;
    private Integer version;
    private String name;
    private String description;
    private String instructions;
    private String toolNamesJson;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
