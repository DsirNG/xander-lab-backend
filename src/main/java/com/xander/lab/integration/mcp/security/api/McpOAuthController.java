package com.xander.lab.integration.mcp.security.api;

import com.xander.lab.integration.mcp.security.McpOAuthException;
import com.xander.lab.integration.mcp.security.McpOAuthProperties;
import com.xander.lab.integration.mcp.security.application.McpOAuthService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;

/** OAuth 2.1 endpoints and discovery metadata for the public remote MCP server. */
@RestController
@RequiredArgsConstructor
public class McpOAuthController {
    private final McpOAuthService oauthService;
    private final McpOAuthProperties properties;

    @GetMapping(value = "/.well-known/oauth-protected-resource", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> protectedResourceMetadata(@RequestParam(required = false) String resource) {
        String base = properties.getPublicBaseUrl();
        String resolvedResource = resource == null || resource.isBlank() ? base + "/api/mcp" : resource;
        if (!List.of(base + "/api/mcp", base + "/api/mcp/csdn", base + "/api/mcp/dual").contains(resolvedResource)) {
            throw new McpOAuthException("invalid_request", 400, "resource is not an Xander MCP endpoint");
        }
        return Map.of(
                "resource", resolvedResource,
                "authorization_servers", List.of(base),
                "scopes_supported", McpOAuthService.SUPPORTED_SCOPES,
                "bearer_methods_supported", List.of("header"));
    }

    @GetMapping(value = "/.well-known/oauth-authorization-server", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> authorizationServerMetadata() {
        String base = properties.getPublicBaseUrl();
        return Map.of(
                "issuer", base,
                "authorization_endpoint", base + "/api/mcp/oauth/authorize",
                "token_endpoint", base + "/api/mcp/oauth/token",
                "registration_endpoint", base + "/api/mcp/oauth/register",
                "revocation_endpoint", base + "/api/mcp/oauth/revoke",
                "response_types_supported", List.of("code"),
                "grant_types_supported", List.of("authorization_code", "refresh_token"),
                "code_challenge_methods_supported", List.of("S256"),
                "token_endpoint_auth_methods_supported", List.of("none"),
                "scopes_supported", McpOAuthService.SUPPORTED_SCOPES);
    }

    @PostMapping(value = "/api/mcp/oauth/register", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, Object> request) {
        String name = request.get("client_name") == null ? null : String.valueOf(request.get("client_name"));
        List<String> redirectUris = request.get("redirect_uris") instanceof List<?> values
                ? values.stream().map(String::valueOf).toList() : List.of();
        var client = oauthService.registerClient(name, redirectUris);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "client_id", client.clientId(), "client_name", client.clientName(),
                "redirect_uris", client.redirectUris(), "token_endpoint_auth_method", "none"));
    }

    @GetMapping("/api/mcp/oauth/authorize")
    public ResponseEntity<Void> authorize(@RequestParam("response_type") String responseType,
                                          @RequestParam("client_id") String clientId,
                                          @RequestParam("redirect_uri") String redirectUri,
                                          @RequestParam String scope,
                                          @RequestParam String state,
                                          @RequestParam("code_challenge") String codeChallenge,
                                          @RequestParam("code_challenge_method") String codeChallengeMethod,
                                          @RequestParam(required = false) String resource) {
        var authorization = oauthService.startAuthorization(responseType, clientId, redirectUri, scope, state,
                codeChallenge, codeChallengeMethod, resource);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(authorization.consentUrl())).build();
    }

    @GetMapping("/api/mcp/oauth/authorize/requests/{requestId}")
    public McpOAuthService.AuthorizationRequestView pending(@PathVariable String requestId,
                                                             @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return oauthService.pendingAuthorization(requestId, authorization);
    }

    @PostMapping("/api/mcp/oauth/authorize/requests/{requestId}/approve")
    public Map<String, String> approve(@PathVariable String requestId,
                                       @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return Map.of("redirectUrl", oauthService.approveAuthorization(requestId, authorization));
    }

    @PostMapping("/api/mcp/oauth/authorize/requests/{requestId}/deny")
    public Map<String, String> deny(@PathVariable String requestId,
                                    @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return Map.of("redirectUrl", oauthService.denyAuthorization(requestId, authorization));
    }

    @PostMapping(value = "/api/mcp/oauth/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> token(@RequestParam("grant_type") String grantType,
                                                      @RequestParam("client_id") String clientId,
                                                      @RequestParam(value = "code", required = false) String code,
                                                      @RequestParam(value = "redirect_uri", required = false) String redirectUri,
                                                      @RequestParam(value = "code_verifier", required = false) String codeVerifier,
                                                      @RequestParam(value = "refresh_token", required = false) String refreshToken) {
        var tokens = oauthService.issueToken(grantType, clientId, code, redirectUri, codeVerifier, refreshToken);
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(Map.of(
                "access_token", tokens.accessToken(), "token_type", "Bearer", "expires_in", tokens.expiresIn(),
                "refresh_token", tokens.refreshToken(), "scope", tokens.scope()));
    }

    @PostMapping(value = "/api/mcp/oauth/revoke", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> revoke(@RequestParam String token, @RequestParam("client_id") String clientId) {
        oauthService.revokeToken(token, clientId);
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).build();
    }

    @GetMapping("/api/mcp/oauth/clients")
    public List<McpOAuthService.ClientGrantView> clients(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return oauthService.listClientGrants(authorization);
    }

    @DeleteMapping("/api/mcp/oauth/clients/{clientId}")
    public ResponseEntity<Void> revokeClient(@PathVariable String clientId,
                                              @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        oauthService.revokeClientGrant(clientId, authorization);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(McpOAuthException.class)
    public ResponseEntity<Map<String, String>> oauthError(McpOAuthException exception, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return ResponseEntity.status(exception.status()).body(Map.of("error", exception.error(), "error_description", exception.getMessage()));
    }
}
