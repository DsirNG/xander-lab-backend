package com.xander.lab.agent.infrastructure;

import com.xander.lab.recitation.spi.GradedItemDraft;
import com.xander.lab.recitation.spi.MaterialDraft;
import com.xander.lab.recitation.spi.MaterialFilter;
import com.xander.lab.recitation.spi.MaterialView;
import com.xander.lab.recitation.spi.QuizOutcomeView;
import com.xander.lab.recitation.spi.QuizSessionView;
import com.xander.lab.recitation.spi.RecitationMaterialPort;
import com.xander.lab.recitation.spi.RecitationQuizPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** 通过 core-host 内网契约 API 调用知识库与测验服务。 */
@Component
@ConditionalOnProperty(prefix = "agent.core-client", name = "enabled", havingValue = "true")
public class AgentRecitationHttpAdapter implements RecitationMaterialPort, RecitationQuizPort {
    private static final Logger log = LoggerFactory.getLogger(AgentRecitationHttpAdapter.class);
    private static final String INTERNAL_TOKEN_HEADER = "X-DinQor-Internal-Token";

    private final RestClient restClient;
    private final String internalToken;

    @Autowired
    public AgentRecitationHttpAdapter(RestClient.Builder builder,
                                      AgentCoreClientProperties properties) {
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()) {
            throw new IllegalStateException("agent.core-client.base-url 在启用 core-client 时不能为空");
        }
        if (properties.getInternalToken() == null || properties.getInternalToken().isBlank()) {
            throw new IllegalStateException(
                    "agent.core-client.internal-token 在启用 core-client 时不能为空");
        }
        String baseUrl = properties.getBaseUrl().endsWith("/")
                ? properties.getBaseUrl().substring(0, properties.getBaseUrl().length() - 1)
                : properties.getBaseUrl();
        this.restClient = builder.baseUrl(baseUrl).build();
        this.internalToken = properties.getInternalToken();
    }

    @Override
    public MaterialView createMaterial(Long userId, MaterialDraft draft) {
        return post("/internal/agent/recitation/material/create",
                new MaterialCreateRequest(userId, draft), MaterialView.class);
    }

    @Override
    public List<MaterialView> searchMaterials(Long userId, MaterialFilter filter) {
        MaterialList response = post("/internal/agent/recitation/material/search",
                new MaterialFilterRequest(userId, filter == null ? MaterialFilter.all() : filter),
                MaterialList.class);
        return response == null || response.materials() == null ? List.of() : response.materials();
    }

    @Override
    public long countMaterials(Long userId, MaterialFilter filter) {
        CountResponse response = post("/internal/agent/recitation/material/count",
                new MaterialFilterRequest(userId, filter == null ? MaterialFilter.all() : filter),
                CountResponse.class);
        return response == null ? 0L : response.count();
    }

    @Override
    public MaterialView getMaterial(Long userId, Long materialId) {
        return post("/internal/agent/recitation/material/get",
                new MaterialRequest(userId, materialId), MaterialView.class);
    }

    @Override
    public QuizSessionView openQuiz(Long userId, Long materialId, Long conversationId) {
        return post("/internal/agent/recitation/quiz/open",
                new OpenQuizRequest(userId, materialId, conversationId), QuizSessionView.class);
    }

    @Override
    public QuizOutcomeView recordQuiz(Long userId, Long quizSessionId, Long conversationId,
                                      String verdict, List<GradedItemDraft> items) {
        return post("/internal/agent/recitation/quiz/record",
                new RecordQuizRequest(userId, quizSessionId, conversationId, verdict, items),
                QuizOutcomeView.class);
    }

    private <T> T post(String path, Object body, Class<T> responseType) {
        try {
            return restClient.post()
                    .uri(path)
                    .header(INTERNAL_TOKEN_HEADER, internalToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(responseType);
        } catch (RestClientResponseException exception) {
            HttpStatusCode status = exception.getStatusCode();
            throw new ResponseStatusException(status, "recitation boundary rejected the operation");
        } catch (RestClientException exception) {
            log.warn("core-host recitation boundary unavailable; rejecting recitation operation");
            throw new IllegalStateException("core-host recitation boundary unavailable", exception);
        }
    }

    private record MaterialCreateRequest(Long userId, MaterialDraft draft) {
    }

    private record MaterialFilterRequest(Long userId, MaterialFilter filter) {
    }

    private record MaterialRequest(Long userId, Long materialId) {
    }

    private record OpenQuizRequest(Long userId, Long materialId, Long conversationId) {
    }

    private record RecordQuizRequest(Long userId, Long quizSessionId, Long conversationId,
                                    String verdict, List<GradedItemDraft> items) {
    }

    private record MaterialList(List<MaterialView> materials) {
    }

    private record CountResponse(long count) {
    }
}
