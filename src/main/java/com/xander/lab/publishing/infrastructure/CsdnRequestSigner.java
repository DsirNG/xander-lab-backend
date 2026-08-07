package com.xander.lab.publishing.infrastructure;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/** Signs CSDN web-console API requests with the current public frontend secret. */
final class CsdnRequestSigner {
    private static final String WEB_CONSOLE_SECRET = "9znpamsyl2c7cdrr9sas0le9vbc3r6ba";

    private CsdnRequestSigner() {
    }

    static String sign(String method, String accept, String contentType, List<String> signedHeaders, String path) {
        String canonical = method + "\n" + accept + "\n\n" + contentType + "\n\n"
                + String.join("\n", signedHeaders) + "\n"
                + path;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(WEB_CONSOLE_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to sign CSDN request", e);
        }
    }
}
