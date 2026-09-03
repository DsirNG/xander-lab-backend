package com.xander.lab.agent.api;

import com.xander.lab.agent.application.AgentUserSkillService;
import com.xander.lab.agent.domain.AgentUserSkill;
import com.xander.lab.common.Result;
import com.xander.lab.identity.security.UserContext;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Authenticated API for a user's non-executable agent Skill library. */
@RestController
@RequestMapping("/api/agent/skills")
public class AgentSkillController {
    private final AgentUserSkillService service;
    public AgentSkillController(AgentUserSkillService service) { this.service = service; }
    @GetMapping public Result<List<AgentUserSkill>> list() { return Result.success(service.listActive(UserContext.getUserId())); }
    @PostMapping public Result<AgentUserSkill> create(@Valid @RequestBody AgentSkillRequest request) { return Result.success(service.create(UserContext.getUserId(), request)); }
    @DeleteMapping("/{id}") public Result<Void> archive(@PathVariable Long id) { service.archive(UserContext.getUserId(), id); return Result.success(null); }
}
