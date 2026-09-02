package com.xander.lab.virtualpay.api;

import com.xander.lab.common.Result;
import com.xander.lab.identity.security.UserContext;
import com.xander.lab.virtualpay.application.VirtualPayService;
import com.xander.lab.virtualpay.infrastructure.VirtualPayProperties;
import com.xander.lab.virtualpay.infrastructure.VirtualPaySigner;
import com.xander.lab.virtualpay.infrastructure.WechatDeliveryNotificationParser;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

/** 小程序虚拟支付接口与微信发货推送入口。 */
@Slf4j
@RestController
@RequestMapping("/api/virtual-pay")
public class VirtualPayController {
    private static final String XML_SUCCESS = "<xml><ErrCode>0</ErrCode><ErrMsg><![CDATA[success]]></ErrMsg></xml>";
    private static final String XML_FAILED = "<xml><ErrCode>1</ErrCode><ErrMsg><![CDATA[failed]]></ErrMsg></xml>";
    private final VirtualPayService service;
    private final VirtualPaySigner signer;
    private final VirtualPayProperties properties;
    private final WechatDeliveryNotificationParser parser;

    public VirtualPayController(VirtualPayService service, VirtualPaySigner signer,
                                VirtualPayProperties properties, WechatDeliveryNotificationParser parser) {
        this.service = service;
        this.signer = signer;
        this.properties = properties;
        this.parser = parser;
    }

    @GetMapping("/products")
    public Result<VirtualPayDtos.Catalog> products() {
        return Result.success(service.catalog());
    }

    @PostMapping("/orders")
    public Result<VirtualPayDtos.PayData> create(@Valid @RequestBody CreateVirtualPayOrderRequest request) {
        Long userId = UserContext.getUserId();
        log.info("virtual pay order create started userId={} productId={}", userId, request.productId());
        try {
            Result<VirtualPayDtos.PayData> result = Result.success(
                    service.createOrder(userId, request.productId(), request.loginCode()));
            log.info("virtual pay order create succeeded userId={} productId={} outTradeNo={}",
                    userId, request.productId(), result.getData().outTradeNo());
            return result;
        } catch (RuntimeException ex) {
            log.warn("virtual pay order create failed userId={} productId={} exception={} reason={}",
                    userId, request.productId(), ex.getClass().getSimpleName(), ex.getMessage());
            throw ex;
        }
    }

    @GetMapping("/orders/{outTradeNo}")
    public Result<VirtualPayDtos.OrderStatus> status(@PathVariable String outTradeNo) {
        return Result.success(service.status(UserContext.getUserId(), outTradeNo));
    }

    @PostMapping("/orders/{outTradeNo}/reconcile")
    public Result<VirtualPayDtos.OrderStatus> reconcile(@PathVariable String outTradeNo) {
        return Result.success(service.reconcile(UserContext.getUserId(), outTradeNo));
    }

    /** 微信明文消息推送入口；signature 验证通过且订单完全匹配后才发放权益。 */
    @PostMapping(value = "/notify", consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE},
            produces = MediaType.APPLICATION_XML_VALUE)
    public String notify(@RequestParam String signature, @RequestParam String timestamp,
                         @RequestParam String nonce, @RequestBody String xml) {
        if (!signer.verifyMessageSignature(signature, properties.getMessageToken(), timestamp, nonce)) {
            log.warn("virtual pay notification rejected reason=invalid-signature");
            return XML_FAILED;
        }
        try {
            service.deliver(parser.parse(xml));
            return XML_SUCCESS;
        } catch (RuntimeException ex) {
            log.warn("virtual pay notification failed reason={}", ex.getMessage());
            return XML_FAILED;
        }
    }

    /** MP 后台首次保存消息推送 URL 时的连通性校验。 */
    @GetMapping(value = "/notify", produces = MediaType.TEXT_PLAIN_VALUE)
    public String verifyNotifyUrl(@RequestParam String signature, @RequestParam String timestamp,
                                  @RequestParam String nonce, @RequestParam String echostr) {
        return signer.verifyMessageSignature(signature, properties.getMessageToken(), timestamp, nonce)
                ? echostr : "invalid signature";
    }
}
