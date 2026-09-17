package com.xander.lab.profile.application;

import com.xander.lab.payment.api.BillingPort;
import com.xander.lab.identity.api.TokenResponse;
import com.xander.lab.identity.application.AuthService;
import com.xander.lab.profile.application.ProfileOverviewService.ProfileOverview;
import com.xander.lab.content.api.ContentPostPort;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProfileOverviewServiceTest {

    @Test
    void aggregatesIdentityPointsAndManagedBlogCount() {
        AuthService authService = mock(AuthService.class);
        BillingPort billingPort = mock(BillingPort.class);
        ContentPostPort contentPostPort = mock(ContentPostPort.class);
        ProfileOverviewService service = new ProfileOverviewService(
                authService, billingPort, contentPostPort);
        TokenResponse.UserInfo user = TokenResponse.UserInfo.builder()
                .username("xander")
                .nickname("XanderDING")
                .build();
        when(authService.getUserInfo(42L)).thenReturn(user);
        when(billingPort.balance(42L)).thenReturn(9_992L);
        when(contentPostPort.countManaged(42L)).thenReturn(12L);

        ProfileOverview result = service.getOverview(42L);

        assertThat(result.user()).isSameAs(user);
        assertThat(result.points()).isEqualTo(9_992L);
        assertThat(result.blogCount()).isEqualTo(12L);
    }
}
