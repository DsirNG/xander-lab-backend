package com.xander.lab.recitation.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 创建用户确认过的背诵材料请求。 */
public record CreateRecitationMaterialRequest(
        @NotBlank(message = "材料标题不能为空")
        @Size(max = 100, message = "材料标题不能超过100个字符")
        String title,
        @NotBlank(message = "背诵原文不能为空")
        @Size(max = 10000, message = "背诵原文不能超过10000个字符")
        String content) {
}
