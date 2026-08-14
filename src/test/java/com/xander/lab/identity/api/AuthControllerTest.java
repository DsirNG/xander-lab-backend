package com.xander.lab.identity.api;

import com.xander.lab.common.Result;
import com.xander.lab.identity.application.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerTest {
    private AuthService authService;
    private AuthController controller;
    private HttpServletRequest httpRequest;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        controller = new AuthController(authService);
        httpRequest = mock(HttpServletRequest.class);
    }

    @Test
    void loginReturnsTokenResponse() {
        LoginRequest request = new LoginRequest();
        TokenResponse token = TokenResponse.builder().accessToken("access").build();
        when(authService.login(eq(request), any())).thenReturn(token);

        Result<TokenResponse> result = controller.login(request, httpRequest);

        assertThat(result.getData()).isSameAs(token);
        verify(authService).login(eq(request), any());
    }

    @Test
    void loginMapsCredentialFailureToBusinessError() {
        LoginRequest request = new LoginRequest();
        when(authService.login(eq(request), any())).thenThrow(new IllegalArgumentException("invalid credentials"));

        Result<TokenResponse> result = controller.login(request, httpRequest);

        assertThat(result.getCode()).isEqualTo(1001);
    }

    @Test
    void refreshFailureUsesRealUnauthorizedStatus() {
        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken("invalid");
        when(authService.refresh("invalid")).thenThrow(new IllegalArgumentException("invalid"));

        ResponseEntity<Result<TokenResponse>> response = controller.refresh(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(401);
    }

    @Test
    void currentUserRequiresAuthorizationHeader() {
        ResponseEntity<Result<TokenResponse.UserInfo>> response = controller.getCurrentUser(null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void logoutExtractsBearerToken() {
        controller.logout("Bearer access-token");

        verify(authService).logout("access-token");
    }

    @Test
    void updateProfileRequiresAuthorizationHeader() {
        ProfileUpdateRequest request = new ProfileUpdateRequest();
        request.setNickname("新昵称");

        ResponseEntity<Result<TokenResponse.UserInfo>> response = controller.updateProfile(null, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(authService, org.mockito.Mockito.never()).updateProfile(anyString(), anyString(), anyString());
    }

    @Test
    void updateProfileReturnsUpdatedUserInfo() {
        ProfileUpdateRequest request = new ProfileUpdateRequest();
        request.setNickname("新昵称");
        request.setAvatar("https://example.com/a.png");
        TokenResponse.UserInfo updated = TokenResponse.UserInfo.builder().nickname("新昵称").build();
        when(authService.updateProfile("Bearer token", "新昵称", "https://example.com/a.png")).thenReturn(updated);

        ResponseEntity<Result<TokenResponse.UserInfo>> response = controller.updateProfile("Bearer token", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getData()).isSameAs(updated);
    }

    @Test
    void updateProfileMapsValidationFailureToBadRequest() {
        ProfileUpdateRequest request = new ProfileUpdateRequest();
        request.setNickname("  ");
        when(authService.updateProfile(eq("Bearer token"), anyString(), any()))
                .thenThrow(new IllegalArgumentException("昵称不能为空"));

        ResponseEntity<Result<TokenResponse.UserInfo>> response = controller.updateProfile("Bearer token", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getMessage()).isEqualTo("昵称不能为空");
    }

    @Test
    void validateRejectsMalformedHeaderWithoutCallingService() {
        Result<Boolean> result = controller.validateToken("access-token");

        assertThat(result.getData()).isFalse();
    }
}
