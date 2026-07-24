package com.xander.lab.controller;

import com.xander.lab.common.Result;
import com.xander.lab.common.UserContext;
import com.xander.lab.dto.emailreminder.EmailReminderCreateRequest;
import com.xander.lab.dto.emailreminder.EmailReminderStatusUpdateRequest;
import com.xander.lab.dto.emailreminder.EmailReminderTaskVO;
import com.xander.lab.service.EmailReminderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/email-reminders")
@RequiredArgsConstructor
public class EmailReminderController {

    private final EmailReminderService reminderService;

    @GetMapping
    public Result<List<EmailReminderTaskVO>> list() {
        return Result.success(reminderService.list(UserContext.getUserId()));
    }

    @PostMapping
    public Result<EmailReminderTaskVO> create(
            @Valid @RequestBody EmailReminderCreateRequest request) {
        return Result.success(reminderService.create(UserContext.getUserId(), request));
    }

    @PatchMapping("/{id}/status")
    public Result<EmailReminderTaskVO> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody EmailReminderStatusUpdateRequest request) {
        return Result.success(reminderService.updateStatus(
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
