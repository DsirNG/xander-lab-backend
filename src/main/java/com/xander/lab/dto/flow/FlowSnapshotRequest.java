package com.xander.lab.dto.flow;

import lombok.Data;

/**
 * 保存画布快照请求体
 * data 字段为前端序列化后的 JSON 字符串
 */
@Data
public class FlowSnapshotRequest {
    /** 快照 JSON 数据（整个画布状态） */
    private String data;
}
