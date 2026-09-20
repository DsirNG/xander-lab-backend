package com.xander.lab.automation.api;

import com.xander.lab.automation.application.BlogContentPlanService;
import com.xander.lab.automation.application.BlogPlanRunDispatcher;
import com.xander.lab.automation.application.BlogPlanRunService;
import com.xander.lab.automation.domain.BlogPlanRun;
import com.xander.lab.common.Result;
import com.xander.lab.auth.core.UserContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/blog-plans")
@RequiredArgsConstructor
public class BlogContentPlanController {

    private final BlogContentPlanService planService;
    private final BlogPlanRunDispatcher dispatcher;
    private final BlogPlanRunService runService;

    /** Immediate manual test trigger; runs through the same pipeline. */
    @PostMapping("/{id}/trigger")
    public Result<BlogPlanRunVO> trigger(@PathVariable Long id) {
        Long userId = UserContext.getUserId();
        BlogPlanRun run = planService.trigger(userId, id);
        dispatcher.dispatchManual(run, userId);
        return Result.success(runService.toRunVO(run));
    }

}
