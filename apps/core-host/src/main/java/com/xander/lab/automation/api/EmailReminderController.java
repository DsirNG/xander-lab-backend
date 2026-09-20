package com.xander.lab.automation.api;

import com.xander.lab.automation.application.EmailReminderService;
import com.xander.lab.common.Result;
import com.xander.lab.auth.core.UserContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/email-reminders")
@RequiredArgsConstructor
public class EmailReminderController {

    private final EmailReminderService reminderService;

    @PostMapping
    public Result<EmailReminderTaskVO> create(
            @Valid @RequestBody EmailReminderCreateRequest request) {
        return Result.success(reminderService.create(UserContext.getUserId(), request));
    }

    @PatchMapping("/{id}/status")
    public Result<EmailReminderTaskVO> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody EmailReminderStatusUpdateRequest request) {
        // updateStatusForView 而不是 updateStatus：后者现在是与 EmailReminderPort 同名同参的
        // 窄方法（返回契约读模型）。HTTP 层要的是前端页面渲染的全字段 VO，所以调这个。
        return Result.success(reminderService.updateStatusForView(
                UserContext.getUserId(),
                id,
                request.getStatus()
        ));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        reminderService.delete(UserContext.getUserId(), id);
        return Result.success();
    }
}
