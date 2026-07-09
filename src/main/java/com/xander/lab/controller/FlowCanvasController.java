package com.xander.lab.controller;

import com.xander.lab.common.Result;
import com.xander.lab.common.UserContext;
import com.xander.lab.dto.flow.FlowSnapshotRequest;
import com.xander.lab.service.FlowCanvasService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * FlowCraft 画布接口
 * 提供画布快照的读取和保存
 */
@Slf4j
@RestController
@RequestMapping("/api/flow/canvases")
@RequiredArgsConstructor
public class FlowCanvasController {

    private final FlowCanvasService canvasService;

    /**
     * 获取画布快照
     * @return 快照 JSON 字符串
     */
    @GetMapping("/{canvasId}/snapshot")
    public Result<String> getSnapshot(@PathVariable Long canvasId) {
        Long userId = UserContext.getUserId();
        String data = canvasService.getSnapshot(canvasId, userId);
        if (data == null) {
            return Result.notFound("画布不存在");
        }
        return Result.success(data);
    }

    /**
     * 保存画布快照
     */
    @PutMapping("/{canvasId}/snapshot")
    public Result<Void> saveSnapshot(@PathVariable Long canvasId, @RequestBody FlowSnapshotRequest req) {
        if (req.getData() == null) {
            return Result.badRequest("快照数据不能为空");
        }
        Long userId = UserContext.getUserId();
        boolean ok = canvasService.saveSnapshot(canvasId, userId, req.getData());
        if (!ok) {
            return Result.notFound("画布不存在");
        }
        return Result.success();
    }
}
