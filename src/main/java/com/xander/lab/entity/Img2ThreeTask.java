package com.xander.lab.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("img2three_task")
public class Img2ThreeTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String status;
    private String stage;
    private String runToken;
    private String title;
    private String referenceMediaUrl;
    private Long referenceMediaAssetId;
    private String analysisJson;
    private String specJson;
    private String factoryCode;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
