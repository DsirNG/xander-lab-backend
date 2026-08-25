package com.xander.lab.recitation.api;

import com.xander.lab.common.Result;
import com.xander.lab.identity.security.UserContext;
import com.xander.lab.recitation.application.KnowledgeQuizService;
import com.xander.lab.recitation.application.RecitationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** 当前登录用户的背诵材料、录音提交与报告查询 API。 */
@RestController
@RequestMapping("/api/recitations")
@RequiredArgsConstructor
public class RecitationController {
    private final RecitationService service;
    private final KnowledgeQuizService quizService;

    @PostMapping("/materials")
    public Result<RecitationMaterialView> createMaterial(@Valid @RequestBody CreateRecitationMaterialRequest request) {
        return Result.success(service.createMaterial(UserContext.getUserId(), request));
    }

    /**
     * 知识列表。搜索、掌握等级与到期过滤都由服务端执行，和智能体 {@code query_knowledge} 走同一条查询路径，
     * 避免"页面能搜到、智能体搜不到"这类两套口径的问题。参数全部可选，不传即返回最近一批全部知识。
     */
    @GetMapping("/materials")
    public Result<List<RecitationMaterialView>> listMaterials(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String masteryLevel,
            @RequestParam(required = false, defaultValue = "false") boolean dueOnly,
            @RequestParam(required = false, defaultValue = "100") int limit) {
        return Result.success(service.searchMaterials(UserContext.getUserId(),
                new RecitationService.MaterialQuery(search, masteryLevel, dueOnly, limit)));
    }

    @GetMapping("/materials/{materialId}")
    public Result<RecitationMaterialView> getMaterial(@PathVariable Long materialId) {
        return Result.success(service.getMaterial(UserContext.getUserId(), materialId));
    }

    @PostMapping(value = "/materials/{materialId}/attempts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<RecitationAttemptView> createAttempt(
            @PathVariable Long materialId,
            @RequestParam("file") MultipartFile file) {
        return Result.success(service.createAttempt(UserContext.getUserId(), materialId, file));
    }

    @GetMapping("/materials/{materialId}/attempts")
    public Result<List<RecitationAttemptView>> listAttempts(@PathVariable Long materialId) {
        return Result.success(service.listAttempts(UserContext.getUserId(), materialId));
    }

    @GetMapping("/attempts/{attemptId}")
    public Result<RecitationAttemptView> getAttempt(@PathVariable Long attemptId) {
        return Result.success(service.getAttempt(UserContext.getUserId(), attemptId));
    }

    /** 某条知识的测验评分记录：概念问答与练习题的成绩单，知识页据此展示最近一次结果。 */
    @GetMapping("/materials/{materialId}/quizzes")
    public Result<List<KnowledgeQuizAttemptView>> listQuizzes(@PathVariable Long materialId) {
        return Result.success(quizService.listQuizzes(UserContext.getUserId(), materialId));
    }
}
