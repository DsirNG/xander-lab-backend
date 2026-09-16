package com.xander.lab.virtualpay.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.identity.application.SubscriptionEntitlementService;
import com.xander.lab.identity.application.WechatPaymentIdentityService;
import com.xander.lab.identity.application.WechatPaymentIdentityService.PaymentIdentity;
import com.xander.lab.virtualpay.api.VirtualPayDtos;
import com.xander.lab.virtualpay.domain.VirtualPayOrder;
import com.xander.lab.virtualpay.domain.VirtualPayOrderStatus;
import com.xander.lab.virtualpay.infrastructure.VirtualPayOrderMapper;
import com.xander.lab.virtualpay.infrastructure.VirtualPaySigner;
import com.xander.lab.virtualpay.infrastructure.WechatDeliveryNotificationParser.DeliveryNotification;
import com.xander.lab.virtualpay.infrastructure.WechatVirtualPayClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;

/** 个人主体虚拟支付应用服务：创建订单、可信发货、用户查单。 */
@Service
public class VirtualPayService {
    private static final String MODE = "short_series_goods";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final VirtualPaySettingsService settingsService;
    private final VirtualPayOrderMapper orderMapper;
    private final VirtualPaySigner signer;
    private final WechatPaymentIdentityService identityService;
    private final SubscriptionEntitlementService entitlementService;
    private final WechatVirtualPayClient wechatClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public VirtualPayService(VirtualPaySettingsService settingsService, VirtualPayOrderMapper orderMapper,
                             VirtualPaySigner signer, WechatPaymentIdentityService identityService,
                             SubscriptionEntitlementService entitlementService,
                             WechatVirtualPayClient wechatClient, ObjectMapper objectMapper,
                             TransactionTemplate transactionTemplate) {
        this.settingsService = settingsService;
        this.orderMapper = orderMapper;
        this.signer = signer;
        this.identityService = identityService;
        this.entitlementService = entitlementService;
        this.wechatClient = wechatClient;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    public VirtualPayDtos.Catalog catalog() {
        VirtualPaySettingsService.ResolvedSettings settings = settingsService.resolve();
        if (settings == null) return new VirtualPayDtos.Catalog(false, List.of());
        List<VirtualPayDtos.Product> products = settings.products().stream()
                .filter(this::validProduct)
                .sorted(Comparator.comparingInt(p -> tierRank(p.tier())))
                .map(p -> new VirtualPayDtos.Product(p.productId(), p.name(), p.tier(),
                        p.goodsPrice(), p.durationDays())).toList();
        return new VirtualPayDtos.Catalog(!products.isEmpty(), products);
    }

    /** 每次创建订单使用全新微信 loginCode，session_key 只参与本次 HMAC 后立即丢弃。 */
    public VirtualPayDtos.PayData createOrder(Long userId, String productId, String loginCode) {
        VirtualPaySettingsService.ResolvedSettings settings = requireSettings();
        VirtualPaySettingsService.Product product = settings.products().stream()
                .filter(p -> p.productId().equals(productId) && validProduct(p)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("订阅商品不存在或未启用"));
        PaymentIdentity identity = identityService.resolve(userId, loginCode);
        String outTradeNo = newOutTradeNo();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("offerId", settings.offerId());
        values.put("buyQuantity", 1);
        values.put("env", 0);
        values.put("currencyType", "CNY");
        values.put("productId", product.productId());
        values.put("goodsPrice", product.goodsPrice());
        values.put("outTradeNo", outTradeNo);
        values.put("attach", outTradeNo);
        try {
            String signData = objectMapper.writeValueAsString(values);
            VirtualPayOrder order = new VirtualPayOrder();
            order.setOutTradeNo(outTradeNo);
            order.setUserId(userId);
            order.setOpenid(identity.openid());
            order.setProductId(product.productId());
            order.setTier(product.tier());
            order.setGoodsPrice(product.goodsPrice());
            order.setQuantity(1);
            order.setDurationDays(product.durationDays());
            order.setStatus(VirtualPayOrderStatus.PENDING.name());
            orderMapper.insert(order);
            return new VirtualPayDtos.PayData(outTradeNo, MODE, signData,
                    signer.paySignature("requestVirtualPayment", signData, settings.appKey()),
                    signer.userSignature(signData, identity.sessionKey()));
        } catch (DuplicateKeyException ex) {
            throw new IllegalStateException("支付订单号冲突，请重试");
        } catch (Exception ex) {
            throw new IllegalStateException("创建虚拟支付订单失败");
        }
    }

    public void deliver(DeliveryNotification notify) {
        if (!"xpay_goods_deliver_notify".equals(notify.event()) || notify.env() != 0) {
            throw new IllegalArgumentException("微信发货通知事件无效");
        }
        transactionTemplate.executeWithoutResult(status -> {
            VirtualPayOrder order = findByOutTradeNo(notify.outTradeNo());
            validateDelivery(order, notify.openid(), notify.productId(), notify.quantity());
            deliverOnce(order, notify.wxOrderId());
        });
    }

    /** 用户主动确认时调用微信查单；客户端 success 本身从不触发发货。 */
    public VirtualPayDtos.OrderStatus reconcile(Long userId, String outTradeNo) {
        VirtualPayOrder order = ownedOrder(userId, outTradeNo);
        if (VirtualPayOrderStatus.PENDING.name().equals(order.getStatus())) {
            // 微信网络调用必须在数据库事务外；确认支付后再用短事务做条件发货。
            WechatVirtualPayClient.QueryResult result = wechatClient.queryOrder(order.getOpenid(), outTradeNo);
            if (result.paid()) {
                if (result.paidFee() != order.getGoodsPrice() * order.getQuantity()) {
                    throw new IllegalStateException("微信订单实付金额与本地订单不一致");
                }
                transactionTemplate.executeWithoutResult(status ->
                        deliverOnce(ownedOrder(userId, outTradeNo), result.wxOrderId()));
                order = ownedOrder(userId, outTradeNo);
            }
        }
        return status(order);
    }

    public VirtualPayDtos.OrderStatus status(Long userId, String outTradeNo) {
        return status(ownedOrder(userId, outTradeNo));
    }

    private void deliverOnce(VirtualPayOrder order, String wxOrderId) {
        if (VirtualPayOrderStatus.DELIVERED.name().equals(order.getStatus())) return;
        if (!VirtualPayOrderStatus.PENDING.name().equals(order.getStatus())) {
            throw new IllegalArgumentException("订单当前状态不允许发货");
        }
        if (wxOrderId == null || wxOrderId.isBlank()) {
            throw new IllegalArgumentException("微信支付订单号不能为空");
        }
        if (orderMapper.markDelivered(order.getId(), wxOrderId) == 1) {
            entitlementService.grant(order.getUserId(), order.getTier(), order.getDurationDays());
        }
    }

    private void validateDelivery(VirtualPayOrder order, String openid, String productId, int quantity) {
        if (!order.getOpenid().equals(openid) || !order.getProductId().equals(productId)
                || order.getQuantity() != quantity) {
            throw new IllegalArgumentException("微信发货通知与本地订单不一致");
        }
    }

    private VirtualPayOrder ownedOrder(Long userId, String outTradeNo) {
        VirtualPayOrder order = findByOutTradeNo(outTradeNo);
        if (!order.getUserId().equals(userId)) throw new IllegalArgumentException("支付订单不存在");
        return order;
    }

    private VirtualPayOrder findByOutTradeNo(String outTradeNo) {
        VirtualPayOrder order = orderMapper.selectOne(new LambdaQueryWrapper<VirtualPayOrder>()
                .eq(VirtualPayOrder::getOutTradeNo, outTradeNo));
        if (order == null) throw new IllegalArgumentException("支付订单不存在");
        return order;
    }

    private VirtualPayDtos.OrderStatus status(VirtualPayOrder order) {
        return new VirtualPayDtos.OrderStatus(order.getOutTradeNo(), order.getStatus(), order.getTier(),
                order.getDeliveredAt());
    }

    private boolean validProduct(VirtualPaySettingsService.Product product) {
        return product.productId() != null && !product.productId().isBlank()
                && product.name() != null && !product.name().isBlank()
                && List.of("PLUS", "PRO", "ULTRA").contains(product.tier())
                && product.goodsPrice() > 0 && product.durationDays() > 0;
    }

    private VirtualPaySettingsService.ResolvedSettings requireSettings() {
        VirtualPaySettingsService.ResolvedSettings settings = settingsService.resolve();
        if (settings == null) throw new IllegalArgumentException("微信虚拟支付尚未在管理台启用");
        return settings;
    }

    private String newOutTradeNo() {
        byte[] random = new byte[5];
        RANDOM.nextBytes(random);
        return "D" + Long.toString(Instant.now().toEpochMilli(), 36).toUpperCase()
                + HexFormat.of().formatHex(random).toUpperCase();
    }

    /** 用户确认的订阅等级顺序：FREE < PLUS < PRO < ULTRA。 */
    private int tierRank(String tier) {
        return switch (tier) {
            case "PLUS" -> 1;
            case "PRO" -> 2;
            case "ULTRA" -> 3;
            default -> 0;
        };
    }
}
