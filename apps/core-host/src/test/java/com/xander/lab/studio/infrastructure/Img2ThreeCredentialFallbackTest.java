package com.xander.lab.studio.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 钉住 img2three 凭证对 blog.agent 的回落**写在 application.yml 的占位符里**。
 *
 * <h2>为什么需要这个测试</h2>
 * <p>若回落写在 {@code Img2ThreeModelClient.resolveApiKey()/resolveModel()/resolveBaseUrl()}
 * 里，读的是 blogagent 域的 {@code BlogAgentProperties} bean —— 也就是"消费方读提供方的
 * {@code @ConfigurationProperties}"（这是禁止的形状，也是
 * {@code studio → blogagent} 那条跨部署单元实现边）。搬成配置层占位符之后，
 * <b>单元测试再也构造不出那条路径</b>：直接 {@code new} 出来的 {@code Img2ThreeProperties}
 * 不经过任何 YAML。所以这里把<b>真实交付的</b> {@code application.yml} 加载进来解析，
 * 证明回落链真的存在 —— 这是"搬家"之后唯一能守住它的东西。</p>
 *
 * <h2>⚠️ 回落的是 blog.agent 的<b>属性值</b>，不是环境变量名</h2>
 * <p>占位符写的是 {@code ${blog.agent.api-key:}} 而不是 {@code ${BLOG_AGENT_API_KEY:}}。
 * 这一点不是风格问题：{@code application-local.yml} 是<b>直接把值写在
 * {@code blog.agent.api-key} 上</b>的（不是设环境变量）。若占位符写环境变量名，
 * 本地那份配置就回落不到 —— 行为会变。{@code alsoFallsBackToABlogAgentPropertyWrittenInAProfileFile}
 * 就是钉这一条的。</p>
 */
class Img2ThreeCredentialFallbackTest {

    private static final String YAML = "application.yml";

    /** 只配了 blog.agent（环境变量形态）时，img2three 的凭证必须整体回落到它。 */
    @Test
    void fallsBackToBlogAgentCredentialsWhenImg2ThreeIsNotConfigured() throws Exception {
        PropertySourcesPropertyResolver resolver = resolver(Map.of(
                "BLOG_AGENT_BASE_URL", "https://blog.example/v1",
                "BLOG_AGENT_API_KEY", "blog-env-key",
                "BLOG_AGENT_MODEL", "blog-env-model"));

        assertThat(resolver.getProperty("img2three.agent.base-url")).isEqualTo("https://blog.example/v1");
        assertThat(resolver.getProperty("img2three.agent.api-key")).isEqualTo("blog-env-key");
        assertThat(resolver.getProperty("img2three.agent.model")).isEqualTo("blog-env-model");
    }

    /** img2three 自己配了就必须用自己的，回落不能盖掉它。 */
    @Test
    void img2ThreeOwnSettingsWinOverTheFallback() throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("BLOG_AGENT_API_KEY", "blog-env-key");
        values.put("BLOG_AGENT_MODEL", "blog-env-model");
        values.put("IMG2THREE_AGENT_API_KEY", "img2three-key");
        values.put("IMG2THREE_AGENT_MODEL", "img2three-model");

        PropertySourcesPropertyResolver resolver = resolver(values);

        assertThat(resolver.getProperty("img2three.agent.api-key")).isEqualTo("img2three-key");
        assertThat(resolver.getProperty("img2three.agent.model")).isEqualTo("img2three-model");
    }

    /**
     * {@code application-local.yml} 把 {@code blog.agent.*} 写成<b>属性</b>（不是环境变量）。
     * 回落必须也能接上它 —— 这是 Java 版回落的实际行为，也是本仓库
     * {@code application-local.yml} 的真实形态（它只设 blog.agent.*，不设 img2three.agent.*）。
     */
    @Test
    void alsoFallsBackToABlogAgentPropertyWrittenInAProfileFile() throws Exception {
        MutablePropertySources sources = yamlSources();
        sources.addFirst(new MapPropertySource("simulated-application-local.yml", Map.of(
                "blog.agent.base-url", "https://api.leorix.top/v1",
                "blog.agent.api-key", "local-file-key",
                "blog.agent.model", "local-file-model")));

        PropertySourcesPropertyResolver resolver = new PropertySourcesPropertyResolver(sources);

        assertThat(resolver.getProperty("img2three.agent.base-url")).isEqualTo("https://api.leorix.top/v1");
        assertThat(resolver.getProperty("img2three.agent.api-key")).isEqualTo("local-file-key");
        assertThat(resolver.getProperty("img2three.agent.model")).isEqualTo("local-file-model");
    }

    private PropertySourcesPropertyResolver resolver(Map<String, Object> values) throws Exception {
        MutablePropertySources sources = yamlSources();
        sources.addFirst(new MapPropertySource("simulated-environment", values));
        return new PropertySourcesPropertyResolver(sources);
    }

    /** 加载真实交付的 application.yml（它在 main 的 resources 里，测试 classpath 上直接可见）。 */
    private MutablePropertySources yamlSources() throws Exception {
        MutablePropertySources sources = new MutablePropertySources();
        List<PropertySource<?>> loaded = new YamlPropertySourceLoader()
                .load(YAML, new ClassPathResource(YAML));
        loaded.forEach(sources::addLast);
        return sources;
    }
}
