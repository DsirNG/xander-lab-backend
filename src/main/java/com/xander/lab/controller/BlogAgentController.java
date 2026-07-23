package com.xander.lab.controller;

import com.xander.lab.common.Result;
import com.xander.lab.common.UserContext;
import com.xander.lab.dto.agent.BlogAgentTaskCreateRequest;
import com.xander.lab.dto.agent.BlogAgentTaskVO;
import com.xander.lab.entity.BlogAgentTask;
import com.xander.lab.service.BlogAgentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/blog-agent/tasks")
@RequiredArgsConstructor
public class BlogAgentController {
    private final BlogAgentService service;

    @PostMapping
    public Result<BlogAgentTask> create(@Valid @RequestBody BlogAgentTaskCreateRequest request) {
        return Result.success(service.create(UserContext.getUserId(), request));
    }

    @GetMapping("/{id}")
    public Result<BlogAgentTaskVO> get(@PathVariable Long id) {
        return Result.success(service.get(id, UserContext.getUserId()));
    }

    @PostMapping("/{id}/run")
    public Result<BlogAgentTaskVO> run(@PathVariable Long id) {
        return Result.success(service.run(id, UserContext.getUserId()));
    }

}
