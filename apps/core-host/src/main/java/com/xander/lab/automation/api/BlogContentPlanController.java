package com.xander.lab.automation.api;

import com.xander.lab.automation.application.BlogContentPlanService;
import com.xander.lab.automation.application.BlogPlanRunDispatcher;
import com.xander.lab.automation.application.BlogPlanRunService;
import com.xander.lab.automation.domain.BlogPlanRun;
import com.xander.lab.common.Result;
import com.xander.lab.auth.core.UserContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/blog-plans")
@RequiredArgsConstructor
public class BlogContentPlanController {

    private final BlogContentPlanService planService;
    private final BlogPlanRunDispatcher dispatcher;
    private final BlogPlanRunService runService;

    @PostMapping
    public Result<BlogContentPlanVO> create(@Valid @RequestBody BlogContentPlanCreateRequest request) {
        return Result.success(planService.create(UserContext.getUserId(), request));
    }

    /** AI 生成计划: creates `days` one-shot plans, one per day from tomorrow. */
    @PostMapping("/ai-generate")
    public Result<List<BlogContentPlanVO>> aiGenerate(
            @Valid @RequestBody BlogContentPlanAiGenerateRequest request) {
        return Result.success(planService.aiGenerate(UserContext.getUserId(), request));
    }

    @GetMapping
    public Result<BlogContentPlansPage> list(@RequestParam(defaultValue = "1") long page,
                                             @RequestParam(defaultValue = "10") long size) {
        return Result.success(planService.list(UserContext.getUserId(), page, size));
    }

    @GetMapping("/{id}")
    public Result<BlogContentPlanVO> detail(@PathVariable Long id) {
        return Result.success(planService.detail(UserContext.getUserId(), id));
    }

    @PatchMapping("/{id}")
    public Result<BlogContentPlanVO> update(@PathVariable Long id,
                                            @Valid @RequestBody BlogContentPlanUpdateRequest request) {
        return Result.success(planService.update(UserContext.getUserId(), id, request));
    }

    @PatchMapping("/{id}/status")
    public Result<BlogContentPlanVO> updateStatus(@PathVariable Long id,
                                                  @Valid @RequestBody BlogContentPlanStatusRequest request) {
        // updateStatusForView 而不是 updateStatus：后者现在是与 ContentPlanPort 同名同参的
        // 窄方法（返回契约读模型）。HTTP 层要的是前端页面渲染的全字段 VO，所以调这个。
        return Result.success(planService.updateStatusForView(UserContext.getUserId(), id, request.getAction()));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        planService.delete(UserContext.getUserId(), id);
        return Result.success();
    }

    /** Immediate manual test trigger; runs through the same pipeline. */
    @PostMapping("/{id}/trigger")
    public Result<BlogPlanRunVO> trigger(@PathVariable Long id) {
        Long userId = UserContext.getUserId();
        BlogPlanRun run = planService.trigger(userId, id);
        dispatcher.dispatchManual(run, userId);
        return Result.success(runService.toRunVO(run));
    }

    /** Asks the agent to refine a seed topic into a multi-day topic series. */
    @PostMapping("/topics/generate")
    public Result<List<String>> generateTopics(
            @Valid @RequestBody BlogContentPlanTopicsGenerateRequest request) {
        return Result.success(planService.generateTopics(UserContext.getUserId(), request));
    }
}
