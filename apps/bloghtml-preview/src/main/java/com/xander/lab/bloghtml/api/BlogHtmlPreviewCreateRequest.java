package com.xander.lab.bloghtml.api;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request to create a blog HTML preview session. The code is untrusted user
 * content and is only ever served as a standalone sandbox page.
 */
@Data
public class BlogHtmlPreviewCreateRequest {

    /** The raw HTML (or fragment) to preview. Size is enforced by the service. */
    @NotBlank(message = "HTML 代码不能为空")
    private String code;
}