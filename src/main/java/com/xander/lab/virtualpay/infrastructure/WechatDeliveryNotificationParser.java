package com.xander.lab.virtualpay.infrastructure;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;

/** 安全解析微信明文 XML 发货推送；禁用外部实体以避免 XXE。 */
@Component
public class WechatDeliveryNotificationParser {

    public DeliveryNotification parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            return new DeliveryNotification(text(document, "Event"), text(document, "OpenId"),
                    text(document, "OutTradeNo"), firstNonBlank(text(document, "MchOrderNo"),
                    text(document, "WxOrderId")), text(document, "ProductId"),
                    integer(document, "Quantity", 1), integer(document, "Env", -1));
        } catch (Exception ex) {
            throw new IllegalArgumentException("微信发货通知格式无效");
        }
    }

    private String text(Document document, String tag) {
        NodeList nodes = document.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent();
    }

    private int integer(Document document, String tag, int fallback) {
        try {
            String value = text(document, tag);
            return value == null ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    /** 微信道具发货通知中完成订单校验和幂等所需字段。 */
    public record DeliveryNotification(String event, String openid, String outTradeNo,
                                       String wxOrderId, String productId, int quantity, int env) {
    }
}
