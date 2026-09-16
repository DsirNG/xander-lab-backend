package com.xander.lab.virtualpay.infrastructure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WechatDeliveryNotificationParserTest {
    private final WechatDeliveryNotificationParser parser = new WechatDeliveryNotificationParser();

    @Test
    void parsesDeliveryFieldsIncludingNestedGoodsAndWechatOrder() {
        String xml = """
                <xml><Event>xpay_goods_deliver_notify</Event><OpenId>openid-1</OpenId>
                <OutTradeNo>DORDER1</OutTradeNo><Env>0</Env>
                <WeChatPayInfo><MchOrderNo>WXORDER1</MchOrderNo></WeChatPayInfo>
                <GoodsInfo><ProductId>plus_month</ProductId><Quantity>1</Quantity></GoodsInfo></xml>
                """;

        var notification = parser.parse(xml);

        assertThat(notification.event()).isEqualTo("xpay_goods_deliver_notify");
        assertThat(notification.openid()).isEqualTo("openid-1");
        assertThat(notification.wxOrderId()).isEqualTo("WXORDER1");
        assertThat(notification.productId()).isEqualTo("plus_month");
        assertThat(notification.quantity()).isEqualTo(1);
        assertThat(notification.env()).isZero();
    }

    @Test
    void rejectsDoctypeAndExternalEntityPayloads() {
        assertThatThrownBy(() -> parser.parse("<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><xml>&e;</xml>"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
