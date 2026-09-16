package com.xander.lab.integration.mcp.juejin.api;

import com.xander.lab.common.Result;
import com.xander.lab.auth.core.UserContext;
import com.xander.lab.integration.mcp.juejin.application.JuejinCatalogService;
import com.xander.lab.integration.mcp.juejin.application.JuejinCredentialService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** C-end API for manually synchronizing Juejin categories and tags catalog. */
@RestController
@RequestMapping("/api/publishing/juejin/catalog")
@RequiredArgsConstructor
public class JuejinCatalogController {
    private final JuejinCatalogService catalogService;
    private final JuejinCredentialService credentialService;

    @PostMapping("/sync")
    public Result<Void> syncCatalog() {
        Long userId = UserContext.getUserId();
        if (userId == null) throw new IllegalStateException("Authentication is required");
        
        catalogService.sync(credentialService.requireCredential(userId));
        return Result.success(null);
    }
}
