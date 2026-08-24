package com.xander.lab.agent.api;

import com.xander.lab.agent.domain.AgentAttachment;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Validated attachment metadata returned by the agent upload endpoint. */
public record AgentAttachmentRequest(
        @NotBlank(message = "附件地址不能为空")
        @Size(max = 2048, message = "附件地址过长")
        @Pattern(regexp = "^https://.+", message = "附件地址必须使用 HTTPS")
        String url,
        @NotBlank(message = "附件名称不能为空")
        @Size(max = 255, message = "附件名称不能超过255个字符")
        String name,
        @NotBlank(message = "附件类型不能为空")
        @Size(max = 120, message = "附件类型过长")
        String contentType,
        @PositiveOrZero(message = "附件大小无效")
        @Max(value = 20971520, message = "单个附件不能超过20MB")
        long size) {

    public AgentAttachment toDomain() {
        return new AgentAttachment(url.trim(), name.trim(), contentType.trim(), size);
    }
}
