package com.xander.lab.controller;

import com.xander.lab.common.Result;
import com.xander.lab.common.UserContext;
import com.xander.lab.dto.PageData;
import com.xander.lab.dto.flow.FlowProjectCreateRequest;
import com.xander.lab.dto.flow.FlowProjectUpdateRequest;
import com.xander.lab.dto.flow.FlowProjectVO;
import com.xander.lab.service.FlowProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * FlowCraft 项目接口
 * 提供项目的 CRUD 操作
 */
@Slf4j
@RestController
@RequestMapping("/api/flow/projects")
@RequiredArgsConstructor
public class FlowProjectController {

    private final FlowProjectService projectService;

    /**
     * 获取当前用户的项目列表
     * @param page     页码，默认 1
     * @param pageSize 每页大小，默认 20
     * @param search   搜索关键词（可选）
     */
    @GetMapping
    public Result<PageData<FlowProjectVO>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "") String search) {
        Long userId = UserContext.getUserId();
        return Result.success(projectService.getUserProjects(userId, page, pageSize, search));
    }

    /**
     * 创建新项目
     */
    @PostMapping
    public Result<FlowProjectVO> create(@RequestBody FlowProjectCreateRequest req) {
        if (req.getName() == null || req.getName().isBlank()) {
            return Result.badRequest("项目名称不能为空");
        }
        if (req.getName().length() > 100) {
            return Result.badRequest("项目名称不能超过 100 个字符");
        }
        Long userId = UserContext.getUserId();
        return Result.success(projectService.createProject(userId, req.getName()));
    }

    /**
     * 获取项目详情（含画布列表）
     */
    @GetMapping("/{id}")
    public Result<FlowProjectVO> get(@PathVariable Long id) {
        Long userId = UserContext.getUserId();
        FlowProjectVO vo = projectService.getProject(id, userId);
        if (vo == null) {
            return Result.notFound("项目不存在");
        }
        return Result.success(vo);
    }

    /**
     * 更新项目名称
     */
    @PatchMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody FlowProjectUpdateRequest req) {
        if (req.getName() == null || req.getName().isBlank()) {
            return Result.badRequest("项目名称不能为空");
        }
        if (req.getName().length() > 100) {
            return Result.badRequest("项目名称不能超过 100 个字符");
        }
        Long userId = UserContext.getUserId();
        boolean ok = projectService.updateProject(id, userId, req.getName());
        if (!ok) {
            return Result.notFound("项目不存在");
        }
        return Result.success();
    }

    /**
     * 删除项目
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Long userId = UserContext.getUserId();
        boolean ok = projectService.deleteProject(id, userId);
        if (!ok) {
            return Result.notFound("项目不存在");
        }
        return Result.success();
    }
}
