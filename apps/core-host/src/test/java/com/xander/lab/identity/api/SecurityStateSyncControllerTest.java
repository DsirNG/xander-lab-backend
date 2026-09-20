package com.xander.lab.identity.api;

import com.xander.lab.identity.application.SecurityStateSyncProperties;
import com.xander.lab.identity.domain.AccountSecurityState;
import com.xander.lab.identity.infrastructure.AccountSecurityStateMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecurityStateSyncControllerTest {

    @Test
    void returnsAllStatesAndWatermarkForAuthorizedGateway() {
        AccountSecurityState disabled = state(42L, true, 2L, 9L);
        AccountSecurityState enabled = state(43L, false, 3L, 10L);
        AccountSecurityStateMapper mapper = mock(AccountSecurityStateMapper.class);
        when(mapper.listAll()).thenReturn(List.of(disabled, enabled));
        SecurityStateSyncProperties properties = properties();

        SecurityStateSyncController controller = new SecurityStateSyncController(mapper, properties);

        var snapshot = controller.snapshot("sync-secret");

        assertThat(snapshot.watermark()).isEqualTo(10L);
        assertThat(snapshot.states()).containsExactly(
                new com.xander.lab.core.api.SecurityStateSyncApi.Entry(42L, true, 2L, 9L),
                new com.xander.lab.core.api.SecurityStateSyncApi.Entry(43L, false, 3L, 10L));
    }

    @Test
    void hidesEndpointFromRequestsWithoutTheInternalToken() {
        AccountSecurityStateMapper mapper = mock(AccountSecurityStateMapper.class);
        SecurityStateSyncController controller = new SecurityStateSyncController(mapper, properties());

        assertThatThrownBy(() -> controller.snapshot("wrong-secret"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");
    }

    private SecurityStateSyncProperties properties() {
        SecurityStateSyncProperties properties = new SecurityStateSyncProperties();
        properties.setInternalApiEnabled(true);
        properties.setInternalToken("sync-secret");
        return properties;
    }

    private AccountSecurityState state(long accountId, boolean disabled,
                                       long securityVersion, long changeSeq) {
        AccountSecurityState state = new AccountSecurityState();
        state.setAccountId(accountId);
        state.setDisabled(disabled ? 1 : 0);
        state.setSecurityVersion(securityVersion);
        state.setChangeSeq(changeSeq);
        return state;
    }
}
