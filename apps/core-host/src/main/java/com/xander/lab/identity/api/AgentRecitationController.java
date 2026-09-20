package com.xander.lab.identity.api;

import com.xander.lab.identity.application.SecurityStateSyncProperties;
import com.xander.lab.recitation.spi.GradedItemDraft;
import com.xander.lab.recitation.spi.MaterialDraft;
import com.xander.lab.recitation.spi.MaterialFilter;
import com.xander.lab.recitation.spi.MaterialView;
import com.xander.lab.recitation.spi.QuizOutcomeView;
import com.xander.lab.recitation.spi.QuizSessionView;
import com.xander.lab.recitation.spi.RecitationMaterialPort;
import com.xander.lab.recitation.spi.RecitationQuizPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/** Agent 专用的知识库与测验契约 API；默认关闭，不暴露 recitation 控制器。 */
@RestController
@RequestMapping("/internal/agent/recitation")
@ConditionalOnProperty(prefix = "security.state-sync", name = "internal-api-enabled",
        havingValue = "true")
public class AgentRecitationController {
    private static final String INTERNAL_TOKEN_HEADER = "X-DinQor-Internal-Token";

    private final RecitationMaterialPort materialPort;
    private final RecitationQuizPort quizPort;
    private final SecurityStateSyncProperties properties;

    public AgentRecitationController(RecitationMaterialPort materialPort,
                                     RecitationQuizPort quizPort,
                                     SecurityStateSyncProperties properties) {
        this.materialPort = materialPort;
        this.quizPort = quizPort;
        this.properties = properties;
        if (properties.getInternalToken() == null || properties.getInternalToken().isBlank()) {
            throw new IllegalStateException(
                    "security.state-sync.internal-token 在启用内网知识库 API 时不能为空");
        }
    }

    @PostMapping("/material/create")
    public MaterialView createMaterial(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @RequestBody MaterialCreateRequest request) {
        authorize(token);
        return materialPort.createMaterial(request.userId(), request.draft());
    }

    @PostMapping("/material/search")
    public MaterialList searchMaterials(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @RequestBody MaterialFilterRequest request) {
        authorize(token);
        return new MaterialList(materialPort.searchMaterials(request.userId(), request.filter()));
    }

    @PostMapping("/material/count")
    public CountResponse countMaterials(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @RequestBody MaterialFilterRequest request) {
        authorize(token);
        return new CountResponse(materialPort.countMaterials(request.userId(), request.filter()));
    }

    @PostMapping("/material/get")
    public MaterialView getMaterial(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @RequestBody MaterialRequest request) {
        authorize(token);
        return materialPort.getMaterial(request.userId(), request.materialId());
    }

    @PostMapping("/quiz/open")
    public QuizSessionView openQuiz(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @RequestBody OpenQuizRequest request) {
        authorize(token);
        return quizPort.openQuiz(request.userId(), request.materialId(), request.conversationId());
    }

    @PostMapping("/quiz/record")
    public QuizOutcomeView recordQuiz(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @RequestBody RecordQuizRequest request) {
        authorize(token);
        return quizPort.recordQuiz(request.userId(), request.quizSessionId(), request.conversationId(),
                request.verdict(), request.items());
    }

    private void authorize(String token) {
        if (token == null || !MessageDigest.isEqual(
                properties.getInternalToken().getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not Found");
        }
    }

    public record MaterialCreateRequest(Long userId, MaterialDraft draft) {
    }

    public record MaterialFilterRequest(Long userId, MaterialFilter filter) {
    }

    public record MaterialRequest(Long userId, Long materialId) {
    }

    public record OpenQuizRequest(Long userId, Long materialId, Long conversationId) {
    }

    public record RecordQuizRequest(Long userId, Long quizSessionId, Long conversationId,
                                   String verdict, List<GradedItemDraft> items) {
    }

    public record MaterialList(List<MaterialView> materials) {
    }

    public record CountResponse(long count) {
    }
}
