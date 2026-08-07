package com.xander.lab.identity.api;

import com.xander.lab.common.Result;
import com.xander.lab.identity.application.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerTest {
    private AuthService authService;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        controller = new AuthController(authService);
    }

    @Test
    void loginReturnsTokenResponse() {
        LoginRequest request = new LoginRequest();
        TokenResponse token = TokenResponse.builder().accessToken("access").build();
        when(authService.login(request)).thenReturn(token);

        Result<TokenResponse> result = controller.login(request);

        assertThat(result.getData()).isSameAs(token);
        verify(authService).login(request);
    }

    @Test
    void loginMapsCredentialFailureToBusinessError() {
        LoginRequest request = new LoginRequest();
        when(authService.login(request)).thenThrow(new IllegalArgumentException("invalid credentials"));

        Result<TokenResponse> result = controller.login(request);

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
    void validateRejectsMalformedHeaderWithoutCallingService() {
        Result<Boolean> result = controller.validateToken("access-token");

        assertThat(result.getData()).isFalse();
    }
}
