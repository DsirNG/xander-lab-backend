package com.xander.lab.admin.api;

/**
 * 保存微信登录凭据请求（API 契约）。
 * AppID 与 AppSecret 均必填；AppSecret 仅在本次请求中出现，加密落库后永不下发。
 */
public record SaveWechatCredentialRequest(String appId, String appSecret) {
}