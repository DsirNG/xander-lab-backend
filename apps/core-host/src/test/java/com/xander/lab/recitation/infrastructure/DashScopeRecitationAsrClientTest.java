package com.xander.lab.recitation.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.admin.application.ModelGateway;
import com.xander.lab.recitation.application.RecitationAsrPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DashScopeRecitationAsrClientTest {
    private static final String API_KEY = "unit-test-secret-key";
    private static final String AUDIO_URL = "https://audio.example/private-recording.mp3";

    private HttpClient httpClient;
    private DashScopeRecitationAsrClient client;

    @BeforeEach
    void setUp() {
        httpClient = mock(HttpClient.class);
        ModelGateway.ModelEndpoint endpoint = new ModelGateway.ModelEndpoint(
                5L, "https://workspace.example/api/v1", API_KEY,
                "paraformer-v2", ModelGateway.API_STYLE_DASHSCOPE_ASR);
        ModelGateway gateway = featureKey -> List.of(endpoint);
        client = new DashScopeRecitationAsrClient(gateway, new ObjectMapper(), httpClient);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void pollsAsynchronousTaskWithGet() throws Exception {
        HttpResponse<byte[]> submitted = response(200,
                "{\"output\":{\"task_id\":\"task-1\"}}");
        HttpResponse<byte[]> processing = response(200,
                "{\"output\":{\"task_status\":\"RUNNING\"}}");
        doReturn(submitted, processing).when(httpClient)
                .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

        client.submit(AUDIO_URL);
        RecitationAsrPort.PollResult result = client.poll(5L, "task-1");

        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, times(2)).send(requests.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(requests.getAllValues()).extracting(HttpRequest::method)
                .containsExactly("POST", "GET");
        assertThat(requests.getAllValues().get(1).uri().getPath())
                .isEqualTo("/api/v1/tasks/task-1");
        assertThat(result.state()).isEqualTo(RecitationAsrPort.PollResult.State.PROCESSING);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void exposesOnlySafeDiagnosticsForVendorFailure() throws Exception {
        HttpResponse<byte[]> unauthorized = response(401,
                "{\"code\":\"InvalidApiKey\",\"message\":\"private vendor message\",\"request_id\":\"req-123\"}");
        doReturn(unauthorized).when(httpClient)
                .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

        Throwable error = catchThrowable(() -> client.submit(AUDIO_URL));

        assertThat(error).hasMessageContaining("HTTP 401")
                .hasMessageContaining("InvalidApiKey")
                .hasMessageContaining("req-123")
                .hasMessageNotContaining("private vendor message")
                .hasMessageNotContaining(API_KEY)
                .hasMessageNotContaining(AUDIO_URL);
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<byte[]> response(int status, String body) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return response;
    }
}
