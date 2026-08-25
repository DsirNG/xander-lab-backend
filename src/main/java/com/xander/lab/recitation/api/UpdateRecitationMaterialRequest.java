package com.xander.lab.recitation.api;

import jakarta.validation.constraints.Size;

/**
 * 修改一条已有知识。字段全部可选，只有传了的字段才会被改写（未传即保持原值）。
 *
 * <p>之所以是"部分更新"而不是整体覆盖：改标题这种最常见的操作不应该要求前端把一万字正文
 * 原样回传一遍——回传过程中任何截断或空串都会静默改坏正文。</p>
 *
 * @param title         新标题，传空白视为不修改
 * @param content       新正文；正文实质变化会重置掌握度，因为旧分数衡量的是旧文本
 * @param knowledgeType 新知识类型；只改类型不改测试方式时，测试方式按新类型自动匹配
 * @param testMode      新测试方式；测试方式变化同样重置掌握度
 */
public record UpdateRecitationMaterialRequest(
        @Size(max = 100, message = "材料标题不能超过100个字符")
        String title,
        @Size(max = 10000, message = "背诵原文不能超过10000个字符")
        String content,
        @Size(max = 30, message = "知识类型不能超过30个字符")
        String knowledgeType,
        @Size(max = 30, message = "测试方式不能超过30个字符")
        String testMode) {
}
