package com.xander.lab.integration.mcp.blog.api;

import com.xander.lab.integration.mcp.blog.application.BlogMcpApplicationService;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.ServletRegistrationBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BlogMcpServerConfigurationTest {

    @Test
    void registersTheCanonicalAndCompatibilityMcpPaths() {
        BlogMcpServerConfiguration configuration =
                new BlogMcpServerConfiguration(org.mockito.Mockito.mock(BlogMcpApplicationService.class));

        HttpServletStreamableServerTransportProvider compatibilityTransport = configuration.mcpTransport();
        HttpServletStreamableServerTransportProvider canonicalTransport = configuration.mcpRootTransport();
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> compatibilityServlet =
                configuration.mcpServlet(compatibilityTransport);
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> canonicalServlet =
                configuration.mcpRootServlet(canonicalTransport);

        assertEquals(java.util.Set.of("/api/mcp"), compatibilityServlet.getUrlMappings());
        assertEquals(java.util.Set.of("/mcp"), canonicalServlet.getUrlMappings());
        assertNotNull(configuration.blogMcpServer(compatibilityTransport));
        assertNotNull(configuration.blogMcpRootServer(canonicalTransport));
    }
}
