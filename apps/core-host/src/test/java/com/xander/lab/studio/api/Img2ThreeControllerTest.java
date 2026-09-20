package com.xander.lab.studio.api;

import com.xander.lab.auth.core.UserContext;
import com.xander.lab.common.security.ConnectionRegistry;
import com.xander.lab.common.security.SecurityStateCache;
import com.xander.lab.studio.application.Img2ThreeService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Img2ThreeControllerTest {
    private static final Long ACCOUNT_ID = 42L;
    private static final Long TASK_ID = 7L;

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void runStreamRegistersConnectionAndCleansItAfterBackgroundTaskCompletes() {
        Img2ThreeService service = mock(Img2ThreeService.class);
        SecurityStateCache securityStateCache = new SecurityStateCache();
        ConnectionRegistry connectionRegistry = new ConnectionRegistry();
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        TaskExecutor taskExecutor = submitted::set;
        when(service.runStream(eq(TASK_ID), eq(ACCOUNT_ID), any())).thenReturn(new Img2ThreeTaskVO());
        Img2ThreeController controller = new Img2ThreeController(
                service, taskExecutor, securityStateCache, connectionRegistry);
        UserContext.setUserId(ACCOUNT_ID);

        SseEmitter emitter = controller.runStream(TASK_ID, mock(HttpServletResponse.class));

        assertThat(emitter).isNotNull();
        assertThat(submitted).hasValueSatisfying(Runnable::run);
        verify(service).runStream(eq(TASK_ID), eq(ACCOUNT_ID), any());
        assertThat(connectionRegistry.activeCount(ACCOUNT_ID))
                .as("normal completion must unregister the img2three stream")
                .isZero();
    }

    @Test
    void sendBoundaryClosesEstablishedStreamWhenAccountBecomesDisabled() {
        Img2ThreeService service = mock(Img2ThreeService.class);
        SecurityStateCache securityStateCache = new SecurityStateCache();
        ConnectionRegistry connectionRegistry = new ConnectionRegistry();
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        TaskExecutor taskExecutor = submitted::set;
        when(service.runStream(eq(TASK_ID), eq(ACCOUNT_ID), any())).thenReturn(new Img2ThreeTaskVO());
        Img2ThreeController controller = new Img2ThreeController(
                service, taskExecutor, securityStateCache, connectionRegistry);
        UserContext.setUserId(ACCOUNT_ID);

        controller.runStream(TASK_ID, mock(HttpServletResponse.class));
        assertThat(connectionRegistry.activeCount(ACCOUNT_ID)).isOne();

        securityStateCache.markDisabled(ACCOUNT_ID, 1L);
        submitted.get().run();

        verify(service).runStream(eq(TASK_ID), eq(ACCOUNT_ID), any());
        assertThat(connectionRegistry.activeCount(ACCOUNT_ID))
                .as("the send boundary must close the stream after a local ban")
                .isZero();
    }

    @Test
    void registryCloseUsesAccountDisabledReasonAndIsIdempotent() {
        ConnectionRegistry connectionRegistry = new ConnectionRegistry();
        Img2ThreeController controller = new Img2ThreeController(
                null, null, new SecurityStateCache(), connectionRegistry);
        SseEmitter emitter = spy(new SseEmitter(0L));
        Img2ThreeController.Img2ThreeStream stream = controller.new Img2ThreeStream(
                TASK_ID, ACCOUNT_ID, emitter);
        connectionRegistry.register(stream);

        assertThat(connectionRegistry.closeAll(
                ACCOUNT_ID, ConnectionRegistry.REASON_ACCOUNT_DISABLED)).isOne();
        assertThat(connectionRegistry.closeAll(
                ACCOUNT_ID, ConnectionRegistry.REASON_ACCOUNT_DISABLED)).isZero();
        verify(emitter).completeWithError(any(ResponseStatusException.class));
        assertThat(connectionRegistry.activeCount(ACCOUNT_ID)).isZero();
    }
}
