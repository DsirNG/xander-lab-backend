package com.xander.lab.integration.mcp.client.infrastructure;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 守住出站 MCP 端点校验。
 *
 * <p>这是唯一挡住 SSRF 的地方：端点地址由配置者填写，而服务器会带着它主动发请求。
 * 少挡一类地址，配置者就能拿它探测内网、读云厂商的元数据接口。</p>
 */
class McpEndpointGuardTest {

    @Test
    void acceptsAPublicHttpsEndpoint() {
        URI uri = McpEndpointGuard.validate("https://mcp.example.com/mcp", resolver("93.184.216.34"));

        assertThat(uri.getHost()).isEqualTo("mcp.example.com");
        assertThat(uri.getPath()).isEqualTo("/mcp");
    }

    /** 明文 HTTP 会让远端凭据和工具参数在链路上裸奔。 */
    @Test
    void rejectsPlainHttp() {
        assertThatThrownBy(() -> McpEndpointGuard.validate("http://mcp.example.com/mcp", resolver("93.184.216.34")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    void rejectsLoopback() {
        assertThatThrownBy(() -> McpEndpointGuard.validate("https://localhost/mcp", resolver("127.0.0.1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非公网");
    }

    @Test
    void rejectsPrivateRanges() {
        assertThatThrownBy(() -> McpEndpointGuard.validate("https://mcp.example.com/mcp", resolver("10.0.0.5")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("非公网");
        assertThatThrownBy(() -> McpEndpointGuard.validate("https://mcp.example.com/mcp", resolver("192.168.1.10")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("非公网");
        assertThatThrownBy(() -> McpEndpointGuard.validate("https://mcp.example.com/mcp", resolver("172.16.0.1")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("非公网");
    }

    /** 链路本地地址是云厂商元数据接口（169.254.169.254）所在的地方。 */
    @Test
    void rejectsCloudMetadataEndpoint() {
        assertThatThrownBy(() -> McpEndpointGuard.validate("https://mcp.example.com/mcp", resolver("169.254.169.254")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非公网");
    }

    /** 运营商级 NAT 与基准测试网段在托管网络里是可达的，同样要挡。 */
    @Test
    void rejectsCarrierGradeNatAndBenchmarkRanges() {
        assertThatThrownBy(() -> McpEndpointGuard.validate("https://mcp.example.com/mcp", resolver("100.64.1.1")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("非公网");
        assertThatThrownBy(() -> McpEndpointGuard.validate("https://mcp.example.com/mcp", resolver("198.18.0.1")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("非公网");
    }

    /** IPv6 唯一本地地址（fc00::/7）不在 Java 的站点本地判定里，得单独挡。 */
    @Test
    void rejectsIpv6UniqueLocalAddress() {
        assertThatThrownBy(() -> McpEndpointGuard.validate("https://mcp.example.com/mcp", resolver("fd00::1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非公网");
    }

    /**
     * 一个域名解析出多个地址时，只要有一个是内网就整体拒绝。
     *
     * <p>放行的话，DNS 轮询会让请求落到内网地址上——校验就形同虚设。</p>
     */
    @Test
    void rejectsWhenAnyResolvedAddressIsInternal() {
        assertThatThrownBy(() -> McpEndpointGuard.validate("https://mcp.example.com/mcp",
                resolver("93.184.216.34", "10.0.0.5")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非公网");
    }

    @Test
    void rejectsEmbeddedCredentials() {
        assertThatThrownBy(() -> McpEndpointGuard.validate("https://user:secret@mcp.example.com/mcp",
                resolver("93.184.216.34")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内嵌凭据");
    }

    @Test
    void rejectsUnresolvableHost() {
        assertThatThrownBy(() -> McpEndpointGuard.validate("https://nope.invalid/mcp", unresolvable()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无法解析");
    }

    @Test
    void rejectsBlankAndMalformedInput() {
        assertThatThrownBy(() -> McpEndpointGuard.validate(null, resolver("93.184.216.34")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> McpEndpointGuard.validate("   ", resolver("93.184.216.34")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> McpEndpointGuard.validate("https://mcp.example.com/mcp#frag",
                resolver("93.184.216.34")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fragment");
    }

    private static McpEndpointGuard.HostResolver resolver(String... addresses) {
        return host -> {
            InetAddress[] resolved = new InetAddress[addresses.length];
            for (int index = 0; index < addresses.length; index++) {
                resolved[index] = InetAddress.getByName(addresses[index]);
            }
            return resolved;
        };
    }

    private static McpEndpointGuard.HostResolver unresolvable() {
        return host -> {
            throw new UnknownHostException(host);
        };
    }
}
