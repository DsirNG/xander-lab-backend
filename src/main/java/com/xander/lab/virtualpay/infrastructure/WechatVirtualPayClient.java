package com.xander.lab.virtualpay.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.identity.application.WechatCredentialService;
import com.xander.lab.virtualpay.application.VirtualPaySettingsService;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;

/** 微信 access_token 与 /xpay/query_order 客户端，只暴露归一化查单结果。 */
@Component
public class WechatVirtualPayClient {
    private static final String QUERY_ORDER_URI = "/xpay/query_order";
    private final VirtualPayProperties properties;
    private final VirtualPaySigner signer;
    private final WechatCredentialService credentialService;
    private final VirtualPaySettingsService settingsService;
    private final ObjectMapper objectMapper;
    private final RestClient client;
    private volatile AccessToken cachedToken;

    public WechatVirtualPayClient(VirtualPayProperties properties,
                                  VirtualPaySigner signer,
                                  WechatCredentialService credentialService,
                                  VirtualPaySettingsService settingsService,
                                  ObjectMapper objectMapper) {
        this.properties = properties;
        this.signer = signer;
        this.credentialService = credentialService;
        this.settingsService = settingsService;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.toIntExact(properties.getConnectTimeoutMillis()));
        factory.setReadTimeout(Math.toIntExact(properties.getReadTimeoutMillis()));
        this.client = RestClient.builder().baseUrl(properties.getApiBaseUrl()).requestFactory(factory).build();
    }

    /** 查询现网现金单；请求体字符串生成后不再重新序列化，确保签名与线上字节一致。 */
    public QueryResult queryOrder(String openid, String outTradeNo) {
        try {
            String body = objectMapper.writeValueAsString(new QueryBody(openid, 0, outTradeNo));
            VirtualPaySettingsService.ResolvedSettings settings = settingsService.resolve();
            if (settings == null) throw new IllegalStateException("微信虚拟支付尚未在管理台启用");
            String paySig = signer.paySignature(QUERY_ORDER_URI, body, settings.appKey());
            String accessToken = accessToken();
            String response = client.post()
                    .uri(uriBuilder -> uriBuilder.path(QUERY_ORDER_URI)
                            .queryParam("access_token", accessToken)
                            .queryParam("pay_sig", paySig).build())
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve().body(String.class);
            JsonNode root = objectMapper.readTree(response == null ? "{}" : response);
            if (root.path("errcode").asInt(-1) != 0) {
                return new QueryResult(false, null, 0);
            }
            JsonNode order = root.path("order");
            if (!order.isObject()) return new QueryResult(false, null, 0);
            int state = order.path("status").asInt(-1);
            String wxOrderId = order.path("wx_order_id").asText(null);
            // 2=已支付待发货、3=发货中、4=已发货；1 只是创建成功，绝不能发权益。
            boolean paid = state == 2 || state == 3 || state == 4;
            return new QueryResult(paid, wxOrderId, order.path("paid_fee").asInt(0));
        } catch (Exception ex) {
            throw new IllegalStateException("微信虚拟支付查单暂不可用");
        }
    }

    private synchronized String accessToken() throws Exception {
        AccessToken current = cachedToken;
        if (current != null && current.expiresAt().isAfter(Instant.now().plusSeconds(60))) {
            return current.value();
        }
        WechatCredentialService.ResolvedCredential credential = credentialService.resolve();
        if (credential == null) {
            throw new IllegalStateException("微信小程序凭据未配置");
        }
        String response = client.get().uri(uriBuilder -> uriBuilder.path("/cgi-bin/token")
                        .queryParam("grant_type", "client_credential")
                        .queryParam("appid", credential.appId())
                        .queryParam("secret", credential.appSecret()).build())
                .retrieve().body(String.class);
        JsonNode root = objectMapper.readTree(response == null ? "{}" : response);
        String token = root.path("access_token").asText("");
        if (token.isBlank()) {
            throw new IllegalStateException("微信 access_token 获取失败");
        }
        current = new AccessToken(token, Instant.now().plusSeconds(root.path("expires_in").asLong(7200)));
        cachedToken = current;
        return token;
    }

    private record QueryBody(String openid, int env, String order_id) {
    }

    private record AccessToken(String value, Instant expiresAt) {
    }

    /** paid=true 才允许应用服务进入发货状态迁移。 */
    public record QueryResult(boolean paid, String wxOrderId, int paidFee) {
    }
}
