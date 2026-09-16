package com.xander.lab.virtualpay.infrastructure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VirtualPaySignerTest {
    private final VirtualPaySigner signer = new VirtualPaySigner();

    @Test
    void userSignatureMatchesWechatPublishedVector() {
        String body = "{\"openid\": \"xxx\", \"user_ip\": \"127.0.0.1\", \"env\": 0}";

        assertThat(signer.userSignature(body, "9hAb/NEYUlkaMBEsmFgzig=="))
                .isEqualTo("089d9e8dc5d308977360c4b79ec600a93d736802802a807d634192328032f6c7");
    }

    @Test
    void paymentSignatureIncludesExactUriAndBodyBytes() {
        String body = "{\"offerId\":\"offer\",\"env\":0}";

        assertThat(signer.paySignature("requestVirtualPayment", body, "app-key"))
                .isEqualTo("d7b213ece81d047076d38eea986dabf6a3307a362847a684392b6d5483242674");
        assertThat(signer.paySignature("requestVirtualPayment", body + " ", "app-key"))
                .isNotEqualTo(signer.paySignature("requestVirtualPayment", body, "app-key"));
    }
}
