package com.xander.lab.admin.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 功能模型配置：为某个功能指定主供应商/主模型与兜底供应商/兜底模型。
 * feature_key 全局唯一（uk_feature_model_config_feature）。
 */
@Data
@TableName("feature_model_config")
public class FeatureModelConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 功能标识：agent=智能体对话、blog_agent=博客生成、
     *  blog_agent_image=博客插图、img2three_vision=图生3D 视觉重建（与 ModelGateway.FEATURE_* 保持一致）。 */
    private String featureKey;

    /** 主供应商 model_provider.id；null 表示该功能未配置主模型。 */
    private Long primaryProviderId;

    /** 主供应商使用的主模型名。 */
    private String primaryModel;

    /** 兜底供应商 model_provider.id；主供应商请求重试耗尽后切换，null=不使用兜底。 */
    private Long fallbackProviderId;

    /** 兜底供应商使用的兜底模型名。 */
    private String fallbackModel;

    /** true=启用管理台配置（优先于环境变量），false=回退到环境变量配置。 */
    private Boolean enabled;

    private LocalDateTime updatedAt;
}