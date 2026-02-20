package com.xander.lab.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xander.lab.dto.*;
import com.xander.lab.entity.*;
import com.xander.lab.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 组件库服务层
 * 负责处理组件相关的业务逻辑
 */
@Service
@RequiredArgsConstructor
public class ComponentService {
    private final ComponentCategoryMapper categoryMapper;
    private final ComponentItemMapper itemMapper;
    private final ComponentScenarioMapper scenarioMapper;
    private final ComponentDetailPageMapper detailPageMapper;
    private final UserMapper userMapper;

    /**
     * 获取侧边栏菜单结构
     *
     * @param lang 语言代码 ("zh" 或 "en")
     * @return 包含组件列表的分类列表
     */
    public List<ComponentCategoryVO> getMenu(String lang) {
        boolean isZh = "zh".equalsIgnoreCase(lang);

        // 1. 获取所有分类
        List<ComponentCategory> categories = categoryMapper.selectList(
                new LambdaQueryWrapper<ComponentCategory>().orderByAsc(ComponentCategory::getSort)
        );

        List<ComponentCategoryVO> menu = new ArrayList<>();

        for (ComponentCategory cat : categories) {
            ComponentCategoryVO catVO = new ComponentCategoryVO();
            catVO.setId(cat.getId());
            catVO.setName(isZh ? cat.getNameZh() : cat.getNameEn());
            catVO.setDesc(isZh ? cat.getDescriptionZh() : cat.getDescriptionEn());

            // 2. 获取该分类下的所有组件
            List<ComponentItem> items = itemMapper.selectList(
                    new LambdaQueryWrapper<ComponentItem>()
                            .eq(ComponentItem::getCategoryId, cat.getId())
                            .eq(ComponentItem::getStatus, 1) // 仅获取启用的组件
                            .orderByAsc(ComponentItem::getSort)
            );

            List<ComponentListVO> itemVOs = items.stream().map(item -> {
                ComponentListVO vo = new ComponentListVO();
                vo.setId(item.getId());
                vo.setTitle(isZh ? item.getTitleZh() : item.getTitleEn());
                vo.setDesc(isZh ? item.getDescriptionZh() : item.getDescriptionEn());
                vo.setTag(isZh ? item.getTagZh() : item.getTagEn());
                vo.setIconKey(item.getIconKey());
                vo.setCategoryId(item.getCategoryId());
                vo.setAuthor(item.getAuthor());
                vo.setVersion(item.getVersion());
                return vo;
            }).collect(Collectors.toList());

            catVO.setComponents(itemVOs);
            menu.add(catVO);
        }

        return menu;
    }

    /**
     * 获取组件完整详情（包含场景和子页面）
     *
     * @param id   组件ID
     * @param lang 语言代码
     * @return 组件详情对象
     */
    public ComponentDetailVO getComponentDetail(String id, String lang) {
        boolean isZh = "zh".equalsIgnoreCase(lang);

        ComponentItem item = itemMapper.selectById(id);
        if (item == null) return null;

        ComponentDetailVO vo = new ComponentDetailVO();
        vo.setId(item.getId());
        vo.setTitle(isZh ? item.getTitleZh() : item.getTitleEn());
        vo.setDesc(isZh ? item.getDescriptionZh() : item.getDescriptionEn());
        vo.setTag(isZh ? item.getTagZh() : item.getTagEn());
        vo.setIconKey(item.getIconKey());
        vo.setAuthor(item.getAuthor());
        vo.setVersion(item.getVersion());
        vo.setSourceCode(item.getSourceCode());
        vo.setLibraryCode(item.getLibraryCode());
        vo.setWrapperCode(item.getWrapperCode());

        // 获取演示场景
        List<ComponentScenario> scenarios = scenarioMapper.selectList(
                new LambdaQueryWrapper<ComponentScenario>()
                        .eq(ComponentScenario::getComponentId, id)
                        .orderByAsc(ComponentScenario::getSort)
        );

        List<ComponentScenarioVO> scenarioVOs = scenarios.stream().map(s -> {
            ComponentScenarioVO svo = new ComponentScenarioVO();
            svo.setTitle(isZh ? s.getTitleZh() : s.getTitleEn());
            svo.setDesc(isZh ? s.getDescriptionZh() : s.getDescriptionEn());
            svo.setCode(s.getCodeSnippet());
            svo.setDemoKey(s.getDemoKey());
            svo.setDemoCode(s.getDemoCode());  // 动态沙箱代码
            return svo;
        }).collect(Collectors.toList());

        vo.setScenarios(scenarioVOs);

        // 获取详情子页面配置
        List<ComponentDetailPage> detailPages = detailPageMapper.selectList(
                new LambdaQueryWrapper<ComponentDetailPage>()
                        .eq(ComponentDetailPage::getComponentId, id)
                        .orderByAsc(ComponentDetailPage::getSort)
        );

        List<ComponentDetailPageVO> detailPageVOs = detailPages.stream().map(dp -> {
            ComponentDetailPageVO dvo = new ComponentDetailPageVO();
            dvo.setType(dp.getPageType());
            dvo.setComponentKey(dp.getComponentKey());
            return dvo;
        }).collect(Collectors.toList());

        vo.setDetailPages(detailPageVOs);

        return vo;
    }
    /**
     * 提交分享组件
     *
     * @param dto 分享数据
     * @return 生成的组件ID
     */
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public String shareComponent(ComponentShareDTO dto) {
        // 0. 获取当前用户信息
        Long userId = com.xander.lab.common.UserContext.getUserId();
        String authorName = "匿名用户";
        if (userId != null) {
            User user = userMapper.selectById(userId);
            if (user != null) {
                authorName = user.getNickname() != null ? user.getNickname() : user.getUsername();
            }
        }

        // 1. 生成 ID (将标题转换为小写横杠格式，如 "My Table" -> "my-table")
        String id = dto.getTitleEn().toLowerCase().replaceAll("[^a-z0-9]", "-");
        
        // 检查 ID 是否已存在，若存在则加后缀
        ComponentItem existing = itemMapper.selectById(id);
        if (existing != null) {
            id = id + "-" + (System.currentTimeMillis() % 1000);
        }

        // 2. 创建并保存组件条目
        ComponentItem item = new ComponentItem();
        item.setId(id);
        item.setCategoryId(dto.getCategoryId());
        item.setTitleZh(dto.getTitleZh());
        item.setTitleEn(dto.getTitleEn());
        item.setDescriptionZh(dto.getDescriptionZh());
        item.setDescriptionEn(dto.getDescriptionEn() != null && !dto.getDescriptionEn().isEmpty() ? dto.getDescriptionEn() : dto.getDescriptionZh());
        item.setAuthor(authorName);
        item.setVersion(dto.getVersion() != null ? dto.getVersion() : "1.0.0");
        item.setSourceCode(dto.getSourceCode());
        item.setLibraryCode(dto.getLibraryCode() != null ? dto.getLibraryCode() : "");
        item.setWrapperCode(dto.getWrapperCode() != null ? dto.getWrapperCode() : "");
        item.setStatus(0); // 🚀 默认设为待审核状态 (0)
        item.setSort(100); // 放在后面
        item.setTagZh("社区分享");
        item.setTagEn("Community");
        item.setIconKey("Zap"); // 默认闪电图标
        item.setCreatedAt(java.time.LocalDateTime.now());
        
        itemMapper.insert(item);

        // 3. 批量插入演示场景
        List<ComponentShareDTO.ScenarioDTO> scenarioDTOs = dto.getScenarios();
        for (int i = 0; i < scenarioDTOs.size(); i++) {
            ComponentShareDTO.ScenarioDTO sDto = scenarioDTOs.get(i);
            ComponentScenario scenario = new ComponentScenario();
            scenario.setComponentId(id);
            scenario.setTitleZh(sDto.getTitleZh() != null ? sDto.getTitleZh() : "场景 " + (i + 1));
            scenario.setTitleEn(sDto.getTitleEn() != null && !sDto.getTitleEn().isEmpty() ? sDto.getTitleEn() : "Scenario " + (i + 1));
            scenario.setDescriptionZh(sDto.getDescriptionZh());
            scenario.setDescriptionEn(sDto.getDescriptionEn() != null && !sDto.getDescriptionEn().isEmpty() ? sDto.getDescriptionEn() : sDto.getDescriptionZh());
            scenario.setDemoCode(sDto.getDemoCode());
            scenario.setCodeSnippet(sDto.getCodeSnippet() != null ? sDto.getCodeSnippet() : sDto.getDemoCode());
            scenario.setSort(i + 1);
            scenarioMapper.insert(scenario);
        }

        return id;
    }
}

