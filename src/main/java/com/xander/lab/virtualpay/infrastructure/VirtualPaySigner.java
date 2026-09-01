package com.xander.lab.virtualpay.infrastructure;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** 微信 XPay HMAC-SHA256 与消息推送 SHA-1 签名实现。 */
@Component
public class VirtualPaySigner {

    public String paySignature(String uri, String body, String appKey) {
        return hmacSha256(appKey, uri + "&" + body);
    }

    public String userSignature(String body, String sessionKey) {
        return hmacSha256(sessionKey, body);
    }

    public boolean verifyMessageSignature(String expected, String token, String timestamp, String nonce) {
        if (isBlank(expected) || isBlank(token) || isBlank(timestamp) || isBlank(nonce)) {
            return false;
        }
        try {
            String[] values = {token, timestamp, nonce};
            java.util.Arrays.sort(values);
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            String actual = HexFormat.of().formatHex(
                    digest.digest(String.join("", values).getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),
                    expected.toLowerCase().getBytes(StandardCharsets.US_ASCII));
        } catch (Exception ex) {
            throw new IllegalStateException("微信消息验签初始化失败", ex);
        }
    }

    private String hmacSha256(String key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("微信虚拟支付签名失败", ex);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
