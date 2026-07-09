package com.xander.lab.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xander.lab.entity.FlowCanvas;
import com.xander.lab.entity.FlowProject;
import com.xander.lab.entity.FlowSnapshot;
import com.xander.lab.mapper.FlowCanvasMapper;
import com.xander.lab.mapper.FlowProjectMapper;
import com.xander.lab.mapper.FlowSnapshotMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * FlowCraft 画布服务
 * 处理快照的读取和保存
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlowCanvasService {

    private final FlowCanvasMapper canvasMapper;
    private final FlowSnapshotMapper snapshotMapper;
    private final FlowProjectMapper projectMapper;

    /**
     * 获取画布快照
     * @param canvasId 画布 ID
     * @param userId   当前用户 ID（校验项目归属）
     * @return 快照 JSON 字符串，不存在则返回 null
     */
    public String getSnapshot(Long canvasId, Long userId) {
        if (!hasCanvasPermission(canvasId, userId)) {
            return null;
        }
        FlowSnapshot snapshot = snapshotMapper.selectOne(
                new LambdaQueryWrapper<FlowSnapshot>().eq(FlowSnapshot::getCanvasId, canvasId)
        );
        return snapshot != null ? snapshot.getData() : null;
    }

    /**
     * 保存画布快照（upsert：有则更新，无则插入）
     * @param canvasId 画布 ID
     * @param userId   当前用户 ID
     * @param data     快照 JSON 数据
     * @return 是否保存成功
     */
    public boolean saveSnapshot(Long canvasId, Long userId, String data) {
        if (!hasCanvasPermission(canvasId, userId)) {
            return false;
        }
        FlowSnapshot existing = snapshotMapper.selectOne(
                new LambdaQueryWrapper<FlowSnapshot>().eq(FlowSnapshot::getCanvasId, canvasId)
        );
        if (existing != null) {
            existing.setData(data);
            snapshotMapper.updateById(existing);
        } else {
            FlowSnapshot snapshot = new FlowSnapshot();
            snapshot.setCanvasId(canvasId);
            snapshot.setData(data);
            snapshotMapper.insert(snapshot);
        }
        return true;
    }

    /**
     * 校验画布归属：通过 canvas → project → userId 链路验证
     */
    private boolean hasCanvasPermission(Long canvasId, Long userId) {
        FlowCanvas canvas = canvasMapper.selectById(canvasId);
        if (canvas == null) return false;
        FlowProject project = projectMapper.selectById(canvas.getProjectId());
        return project != null && project.getUserId().equals(userId);
    }
}
