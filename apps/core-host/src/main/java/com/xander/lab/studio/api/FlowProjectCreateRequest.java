package com.xander.lab.studio.api;

import lombok.Data;

/**
 * 创建项目请求体
 */
@Data
public class FlowProjectCreateRequest {
    /** 项目名称（1-100字符） */
    private String name;
}
