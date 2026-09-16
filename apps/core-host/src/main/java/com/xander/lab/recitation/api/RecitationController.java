package com.xander.lab.recitation.api;

import com.xander.lab.common.Result;
import com.xander.lab.auth.core.UserContext;
import com.xander.lab.recitation.application.KnowledgeQuizService;
import com.xander.lab.recitation.application.RecitationService;
import com.xander.lab.recitation.domain.ArchiveFilter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
     * 避免"页面能搜到、智能体搜不到"这类两套口径的问题。参数全部可选，不传即返回最近一批未归档知识。
     *
     * @param archive 归档可见范围 ACTIVE/ARCHIVED/ALL，默认只看未归档
     */
    @GetMapping("/materials")
    public Result<List<RecitationMaterialView>> listMaterials(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String masteryLevel,
            @RequestParam(required = false, defaultValue = "false") boolean dueOnly,
            @RequestParam(required = false, defaultValue = "100") int limit,
            @RequestParam(required = false) String archive) {
        return Result.success(service.searchMaterials(UserContext.getUserId(),
                new RecitationService.MaterialQuery(search, masteryLevel, dueOnly, limit,
                        ArchiveFilter.fromNullable(archive))));
    }

    /** 修改一条知识：只改传了的字段；正文或考法实质变化会重置掌握度。 */
    @PutMapping("/materials/{materialId}")
    public Result<RecitationMaterialView> updateMaterial(
            @PathVariable Long materialId,
            @Valid @RequestBody UpdateRecitationMaterialRequest request) {
        return Result.success(service.updateMaterial(UserContext.getUserId(), materialId, request));
    }

    /** 归档或恢复一条知识：归档后不再出现在默认列表与到期复习中，可随时恢复。 */
    @PostMapping("/materials/{materialId}/archive")
    public Result<RecitationMaterialView> archiveMaterial(
            @PathVariable Long materialId,
            @RequestParam(required = false, defaultValue = "true") boolean archived) {
        return Result.success(service.archiveMaterial(UserContext.getUserId(), materialId, archived));
    }

    /** 彻底删除一条知识及其录音任务、测验留档；不可恢复，前端需二次确认。 */
    @DeleteMapping("/materials/{materialId}")
    public Result<Void> deleteMaterial(@PathVariable Long materialId) {
        service.deleteMaterial(UserContext.getUserId(), materialId);
        return Result.success(null);
    }

    @GetMapping("/materials/{materialId}")
    public Result<RecitationMaterialView> getMaterial(@PathVariable Long materialId) {
        return Result.success(service.getMaterial(UserContext.getUserId(), materialId));
    }

    @PostMapping(value = "/materials/{materialId}/attempts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<RecitationAttemptView> createAttempt(
            @PathVariable Long materialId,
            @RequestParam("file") MultipartFile file,
            @RequestParam String clientRequestId) {
        return Result.success(service.createAttempt(
                UserContext.getUserId(), materialId, file, clientRequestId));
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
