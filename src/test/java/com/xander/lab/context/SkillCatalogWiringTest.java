package com.xander.lab.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.agent.application.AgentSkill;
import com.xander.lab.agent.application.AgentSkillCatalog;
import com.xander.lab.agent.application.AgentToolRegistry;
import com.xander.lab.agent.application.AgentUserSkillService;
import com.xander.lab.agent.application.BuiltinAgentSkills;
import com.xander.lab.agent.application.LoadSkillTool;
import com.xander.lab.agent.infrastructure.AgentUserSkillMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 技能目录的装配冒烟测试。
 *
 * <p>{@link LoadSkillTool} 是一个 AgentTool，会被 {@link AgentToolRegistry} 收集，
 * 而写侧的 {@link AgentUserSkillService} 又要用那张工具表校验技能声明的工具名——
 * 于是"读技能"和"管技能"之间隔着一个构造期循环的可能，只在容器启动时暴露。
 * 目录因此直接拿 {@link AgentUserSkillMapper}，绝不经过 {@code AgentUserSkillService}；
 * 这里把这条边钉住，避免以后有人图省事把服务注进目录或工具。</p>
 */
@SpringBootTest(classes = {
        AgentToolRegistry.class,
        BuiltinAgentSkills.class,
        AgentSkillCatalog.class,
        LoadSkillTool.class,
        AgentUserSkillService.class,
        SkillCatalogWiringTest.JsonConfiguration.class,
})
class SkillCatalogWiringTest {

    @MockBean
    private AgentUserSkillMapper userSkillMapper;

    @Autowired
    private AgentToolRegistry registry;
    @Autowired
    private LoadSkillTool tool;
    @Autowired
    private AgentSkillCatalog catalog;

    @Test
    void theSkillCatalogWiresWithoutACircularDependency() {
        assertThat(registry.get("load_skill")).isSameAs(tool);
        assertThat(registry.allowedNames()).contains("load_skill");
    }

    @Test
    void everyBuiltinSkillReachesTheCatalogWithARoutingDescription() {
        // 目录里的描述就是模型判断"该不该读这一份"的全部依据，缺了它这条技能等于不存在。
        assertThat(catalog.entries(42L)).isNotEmpty().allSatisfy(skill ->
                assertThat(skill.description()).isNotBlank());
        assertThat(catalog.entries(42L)).extracting(AgentSkill::id)
                .contains("blog-writing", "content-publishing", "study-coach",
                        "knowledge-curation", "personal-planning");
    }

    @Configuration
    static class JsonConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
