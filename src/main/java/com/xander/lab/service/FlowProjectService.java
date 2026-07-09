package com.xander.lab.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xander.lab.dto.PageData;
import com.xander.lab.dto.flow.FlowCanvasVO;
import com.xander.lab.dto.flow.FlowProjectVO;
import com.xander.lab.entity.FlowCanvas;
import com.xander.lab.entity.FlowProject;
import com.xander.lab.mapper.FlowCanvasMapper;
import com.xander.lab.mapper.FlowProjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * FlowCraft 项目服务
 * 处理项目 CRUD 及关联画布查询
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlowProjectService {

    private final FlowProjectMapper projectMapper;
    private final FlowCanvasMapper canvasMapper;

    /**
     * 获取用户的项目列表（分页 + 模糊搜索）
     * @param userId   当前用户 ID
     * @param page     页码（从 1 开始）
     * @param pageSize 每页大小
     * @param search   搜索关键词（可为空）
     * @return 分页结果
     */
    public PageData<FlowProjectVO> getUserProjects(Long userId, int page, int pageSize, String search) {
        LambdaQueryWrapper<FlowProject> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FlowProject::getUserId, userId);
        if (search != null && !search.isBlank()) {
            wrapper.like(FlowProject::getName, search);
        }
        wrapper.orderByDesc(FlowProject::getUpdatedAt);

        Page<FlowProject> pageParam = new Page<>(page, pageSize);
        Page<FlowProject> result = projectMapper.selectPage(pageParam, wrapper);

        // 转换为 VO
        Page<FlowProjectVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(result.getRecords().stream().map(this::toVO).toList());
        return PageData.of(voPage);
    }

    /**
     * 创建项目（同时创建默认画布）
     * @param userId 当前用户 ID
     * @param name   项目名称
     * @return 项目 VO（不含画布列表）
     */
    @Transactional
    public FlowProjectVO createProject(Long userId, String name) {
        FlowProject project = new FlowProject();
        project.setUserId(userId);
        project.setName(name);
        projectMapper.insert(project);

        // 创建默认画布
        FlowCanvas canvas = new FlowCanvas();
        canvas.setProjectId(project.getId());
        canvas.setName("默认画布");
        canvasMapper.insert(canvas);

        return toVO(project);
    }

    /**
     * 获取项目详情（含画布列表）
     * @param projectId 项目 ID
     * @param userId    当前用户 ID（校验归属）
     * @return 项目 VO，不存在或不属于该用户则返回 null
     */
    public FlowProjectVO getProject(Long projectId, Long userId) {
        FlowProject project = projectMapper.selectById(projectId);
        if (project == null || !project.getUserId().equals(userId)) {
            return null;
        }

        FlowProjectVO vo = toVO(project);

        // 查询关联画布
        List<FlowCanvas> canvases = canvasMapper.selectList(
                new LambdaQueryWrapper<FlowCanvas>()
                        .eq(FlowCanvas::getProjectId, projectId)
                        .orderByAsc(FlowCanvas::getCreatedAt)
        );
        vo.setCanvases(canvases.stream().map(this::toCanvasVO).toList());
        return vo;
    }

    /**
     * 更新项目名称
     * @return 是否更新成功
     */
    public boolean updateProject(Long projectId, Long userId, String name) {
        FlowProject project = projectMapper.selectById(projectId);
        if (project == null || !project.getUserId().equals(userId)) {
            return false;
        }
        project.setName(name);
        projectMapper.updateById(project);
        return true;
    }

    /**
     * 删除项目（级联删除画布和快照）
     * @return 是否删除成功
     */
    @Transactional
    public boolean deleteProject(Long projectId, Long userId) {
        FlowProject project = projectMapper.selectById(projectId);
        if (project == null || !project.getUserId().equals(userId)) {
            return false;
        }
        // 删除关联画布（快照随画布删除）
        canvasMapper.delete(new LambdaQueryWrapper<FlowCanvas>().eq(FlowCanvas::getProjectId, projectId));
        projectMapper.deleteById(projectId);
        return true;
    }

    /** Entity → VO 转换 */
    private FlowProjectVO toVO(FlowProject p) {
        FlowProjectVO vo = new FlowProjectVO();
        vo.setId(p.getId());
        vo.setName(p.getName());
        vo.setUpdatedAt(p.getUpdatedAt());
        vo.setCreatedAt(p.getCreatedAt());
        return vo;
    }

    /** Canvas Entity → VO 转换 */
    private FlowCanvasVO toCanvasVO(FlowCanvas c) {
        FlowCanvasVO vo = new FlowCanvasVO();
        vo.setId(c.getId());
        vo.setName(c.getName());
        return vo;
    }
}
