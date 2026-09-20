package com.xander.lab.automation.api;

import com.xander.lab.automation.application.EmailReminderWriteService;
import com.xander.lab.common.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/email-reminders")
@RequiredArgsConstructor
public class EmailReminderWriteController {

    private final EmailReminderWriteService reminderWriteService;

    @PostMapping
    public Result<EmailReminderTaskVO> create(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @Valid @RequestBody EmailReminderCreateRequest request) {
        return Result.success(reminderWriteService.create(parseUserId(userId), request));
    }

    @PatchMapping("/{id}/status")
    public Result<EmailReminderTaskVO> updateStatus(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable Long id,
            @Valid @RequestBody EmailReminderStatusUpdateRequest request) {
        return Result.success(reminderWriteService.updateStatus(
                parseUserId(userId), id, request.getStatus()));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable Long id) {
        reminderWriteService.delete(parseUserId(userId), id);
        return Result.success();
    }

    private Long parseUserId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
