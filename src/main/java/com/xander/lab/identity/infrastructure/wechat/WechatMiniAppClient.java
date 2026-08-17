package com.xander.lab.identity.infrastructure.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.identity.application.WechatCredentialService;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 微信小程序 jscode2session 客户端：用登录 code 换取 openid。
 *
 * <p>适配器边界职责：调用微信接口、解析响应为 {@link WechatSession}，把传输异常
 * 归一化为受控异常；不对外暴露微信原始错误细节。凭据（AppID/AppSecret）来自
 * {@link WechatCredentialService}（管理台加密维护），未配置时直接抛出
 * {@link IllegalStateException}，由调用方转成业务错误提示。
 */
@Component
public class WechatMiniAppClient {

    /** 微信返回码为 0 表示成功（与平台约定一致） */
    private static final int SUCCESS_CODE = 0;

    private final WechatMiniAppProperties properties;
    private final WechatCredentialService wechatCredentialService;
    private final RestClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WechatMiniAppClient(WechatMiniAppProperties properties,
                               WechatCredentialService wechatCredentialService) {
        this.properties = properties;
        this.wechatCredentialService = wechatCredentialService;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.toIntExact(properties.getConnectTimeoutMillis()));
        factory.setReadTimeout(Math.toIntExact(properties.getReadTimeoutMillis()));
        // 超时项固定；连接复用对本场景收益低，微信接口单次调用无状态。
        this.client = RestClient.builder()
                .baseUrl(properties.getCode2SessionUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * 用小程序登录 code 换取会话信息 {@link WechatSession}（含 openid）。
     *
     * @param code 小程序端 Taro.login() 取得的临时登录凭证
     * @return 微信会话信息
     * @throws IllegalStateException 凭据未配置、网络失败或微信服务错误（无授权失败场景，均为受控业务失败）
     */
    public WechatSession code2Session(String code) {
        WechatCredentialService.ResolvedCredential credential = wechatCredentialService.resolve();
        if (credential == null) {
            throw new IllegalStateException("微信登录未配置，请联系管理员");
        }
        try {
            String body = client.get()
                    .uri(uriBuilder -> uriBuilder
                            .queryParam("appid", credential.appId())
                            .queryParam("secret", credential.appSecret())
                            .queryParam("js_code", code)
                            .queryParam("grant_type", "authorization_code")
                            .build())
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(body == null ? "{}" : body);
            int errcode = root.path("errcode").asInt(SUCCESS_CODE);
            return new WechatSession(
                    errcode,
                    textOrNull(root, "errmsg"),
                    textOrNull(root, "openid"),
                    textOrNull(root, "session_key"),
                    textOrNull(root, "unionid"));
        } catch (RestClientResponseException ex) {
            // 微信接口异常不应把原始响应透出；统一归为“服务暂不可用”。
            throw new IllegalStateException("微信登录服务暂不可用，请稍后重试");
        } catch (Exception ex) {
            throw new IllegalStateException("微信登录服务暂不可用，请稍后重试");
        }
    }

    private String textOrNull(JsonNode root, String field) {
        JsonNode value = root.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    /**
     * 微信会话信息值对象。
     *
     * @param errcode    微信返回码：0=成功；不为 0 时 errmsg 为失败原因
     * @param errmsg     微信返回的错误描述（调用方仅用于日志，不直接展示给用户）
     * @param openid     用户在本小程序的唯一标识，绑定账号的稳定凭证
     * @param sessionKey 会话密钥（本服务不依赖，保留供未来解密手机号等能力使用）
     * @param unionid    开放平台统一标识，未绑定开放平台时为 null
     */
    public record WechatSession(int errcode, String errmsg, String openid, String sessionKey, String unionid) {

        public boolean isOk() {
            return errcode == SUCCESS_CODE;
        }
    }
}