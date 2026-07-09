package com.xander.lab.dto.flow;

import lombok.Data;

/**
 * 更新项目请求体
 */
@Data
public class FlowProjectUpdateRequest {
    /** 新项目名称 */
    private String name;
}
