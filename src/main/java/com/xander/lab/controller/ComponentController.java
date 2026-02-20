package com.xander.lab.controller;

import com.xander.lab.common.Result;
import com.xander.lab.dto.ComponentCategoryVO;
import com.xander.lab.dto.ComponentDetailVO;
import com.xander.lab.dto.ComponentShareDTO;
import com.xander.lab.service.ComponentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 组件库控制器
 * 提供组件菜单和详情的接口
 */
@RestController
@RequestMapping("/components")
@RequiredArgsConstructor
public class ComponentController {

    private final ComponentService componentService;

    /**
     * 获取侧边栏菜单结构
     *
     * @param lang 语言代码 (zh/en)，默认 zh
     * @return 组件分类列表（包含组件）
     */
    @GetMapping("/menu")
    public Result<List<ComponentCategoryVO>> getMenu(@RequestParam(defaultValue = "zh") String lang) {
        return Result.success(componentService.getMenu(lang));
    }

    /**
     * 获取组件详情
     *
     * @param id   组件ID (例如: toast)
     * @param lang 语言代码 (zh/en)，默认 zh
     * @return 组件详情信息
     */
    @GetMapping("/{id}")
    public Result<ComponentDetailVO> getComponent(@PathVariable String id, @RequestParam(defaultValue = "zh") String lang) {
        ComponentDetailVO vo = componentService.getComponentDetail(id, lang);
        if (vo == null) {
            return Result.error("未找到该组件");
        }
        return Result.success(vo);
    }

    /**
     * 提交分享组件
     *
     * @param dto 分享项数据
     * @return 成功返回组件ID
     */
    @PostMapping("/share")
    public Result<String> shareComponent(@RequestBody ComponentShareDTO dto) {
        String id = componentService.shareComponent(dto);
        return Result.success(id);
    }
}

