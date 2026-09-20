package com.xander.lab.agent.tool.mcp.infrastructure;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * 出站 MCP 端点校验。
 *
 * <p>端点地址由配置者填写，而服务器会带着它主动发起 HTTP 请求。不做校验，就等于给任何能改这份
 * 配置的人一个探测内网的口子（SSRF）。规则与 {@code BlogAgentImageClient} 的下载地址校验同源：
 * 只放行无内嵌凭据的 HTTPS，且域名解析出的**每一个**地址都必须是公网地址。</p>
 *
 * <p>已知残余风险：这里是「先解析、后校验」，而真正建连时 {@code java.net.http.HttpClient}
 * 会再解析一次。理论上存在 DNS rebinding——第一次解析返回公网地址骗过校验，建连时解析返回内网地址。
 * 要彻底堵死需要把校验出的 IP 钉进连接过程，代价是自建 HttpClient 连接层。当前接受这个残余风险，
 * 因为改这份配置需要管理员或账号本人权限，不是匿名可达的攻击面。</p>
 */
public final class McpEndpointGuard {

    private McpEndpointGuard() {
    }

    /** 解析主机名，抽出来是为了让校验逻辑可单测——不依赖真实 DNS。 */
    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /** 校验并归一化端点地址；不通过时抛出带可读原因的参数异常。 */
    public static URI validate(String rawUrl, HostResolver resolver) {
        if (rawUrl == null || rawUrl.isBlank()) throw new IllegalArgumentException("端点地址不能为空");
        URI uri;
        try {
            uri = URI.create(rawUrl.strip());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("端点地址不是合法 URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("端点地址必须是 HTTPS：明文 HTTP 会让远端凭据和工具参数在链路上裸奔");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) throw new IllegalArgumentException("端点地址缺少主机名");
        if (uri.getRawUserInfo() != null) throw new IllegalArgumentException("端点地址不能内嵌凭据，凭据请填在请求头里");
        if (uri.getRawFragment() != null) throw new IllegalArgumentException("端点地址不能带 fragment");

        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(uri.getHost());
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("端点地址无法解析：" + uri.getHost());
        }
        if (addresses == null || addresses.length == 0) {
            throw new IllegalArgumentException("端点地址无法解析：" + uri.getHost());
        }
        for (InetAddress address : addresses) {
            if (address == null || isBlockedAddress(address)) {
                throw new IllegalArgumentException("端点地址指向非公网地址，已拒绝：" + uri.getHost());
            }
        }
        return uri;
    }

    /** 判断地址是否属于必须拒绝的范围：回环、内网、链路本地、多播，以及常见的特殊用途网段。 */
    static boolean isBlockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) {
            // Java 的站点本地判定不含 IPv6 唯一本地地址 fc00::/7。
            return (bytes[0] & 0xfe) == 0xfc;
        }
        if (bytes.length != 4) return true;
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        // 拒绝托管网络里常被路由的 IPv4 特殊用途网段。
        return first == 0
                || (first == 100 && second >= 64 && second <= 127)
                || (first == 198 && (second == 18 || second == 19))
                || first >= 224;
    }
}
