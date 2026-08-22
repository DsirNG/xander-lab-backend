package com.xander.lab.recitation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 用户确认后的中文背诵原文。 */
@Data
@TableName("recitation_material")
public class RecitationMaterial {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String title;
    private String content;
    private String normalizedContent;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
