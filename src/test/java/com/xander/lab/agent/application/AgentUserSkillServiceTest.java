package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xander.lab.agent.api.AgentToolOption;
import com.xander.lab.agent.infrastructure.AgentUserSkillMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 守住技能编排界面拿到的工具清单。
 *
 * <p>{@code create} 会拿 {@code toolRegistry.allowedNames(userId)} 严格求交，所以清单必须与它同源：
 * 界面上选得出的名字一定得能通过校验，否则用户填完整张表才吃一记 400，
 * 而没有任何地方能查到合法取值。</p>
 */
class AgentUserSkillServiceTest {

    private static final long USER_ID = 7L;

    @Test
    void toolOptionsMirrorTheRegisteredToolTable() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of(
                tool("remember"), tool("emit_artifact"), tool("query_posts")));

        List<AgentToolOption> options = service(registry).listToolOptions(USER_ID);

        assertThat(options).extracting(AgentToolOption::name)
                .containsExactlyElementsOf(registry.names());
        assertThat(options).allSatisfy(option -> assertThat(option.description()).isNotBlank());
    }

    /** 一个工具都没注册时给空清单，而不是抛异常让整个技能页打不开。 */
    @Test
    void anEmptyRegistryYieldsNoOptionsRatherThanFailing() {
        assertThat(service(new AgentToolRegistry(List.of())).listToolOptions(USER_ID)).isEmpty();
    }

    /**
     * 清单按用户收窄：别人配的远端工具不能出现在我的可选列表里。
     *
     * <p>否则界面上会给出一个我写了也调不动的工具名——{@code create} 那一步才拒绝，
     * 而用户根本看不出为什么。</p>
     */
    @Test
    void toolOptionsOnlyExposeToolsVisibleToTheCaller() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool("remember")));
        registry.setDynamicToolSources(List.of(userId -> USER_ID == userId
                ? List.of(tool("mcp__mine__do_thing"))
                : List.of()));

        assertThat(service(registry).listToolOptions(USER_ID)).extracting(AgentToolOption::name)
                .containsExactly("mcp__mine__do_thing", "remember");
        assertThat(service(registry).listToolOptions(99L)).extracting(AgentToolOption::name)
                .containsExactly("remember");
    }

    private AgentUserSkillService service(AgentToolRegistry registry) {
        return new AgentUserSkillService(mock(AgentUserSkillMapper.class), registry,
                AgentSkillCatalog.empty(), new ObjectMapper());
    }

    private AgentTool tool(String name) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return name + " 的用途";
            }

            @Override
            public JsonNode parametersSchema() {
                return JsonNodeFactory.instance.objectNode();
            }

            @Override
            public JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress) {
                return JsonNodeFactory.instance.objectNode();
            }
        };
    }
}
