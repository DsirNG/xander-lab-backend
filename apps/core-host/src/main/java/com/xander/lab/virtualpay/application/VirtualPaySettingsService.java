package com.xander.lab.virtualpay.application;

import com.xander.lab.admin.api.SaveVirtualPaySettingsRequest;
import com.xander.lab.identity.infrastructure.wechat.WechatCredentialCipher;
import com.xander.lab.virtualpay.api.VirtualPaySettingsView;
import com.xander.lab.virtualpay.domain.VirtualPaySettings;
import com.xander.lab.virtualpay.infrastructure.VirtualPaySettingsMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;

/** 管理台虚拟支付配置服务：AppKey 加密落库，运行时按请求读取最新设置。 */
@Service
@RequiredArgsConstructor
public class VirtualPaySettingsService {
    private static final long SETTINGS_ID = 1L;
    private final VirtualPaySettingsMapper mapper;
    private final WechatCredentialCipher cipher;

    @Transactional(readOnly = true)
    public VirtualPaySettingsView adminView() {
        VirtualPaySettings row = mapper.selectById(SETTINGS_ID);
        if (row == null) return emptyView();
        return new VirtualPaySettingsView(Boolean.TRUE.equals(row.getEnabled()), row.getOfferId(),
                StringUtils.hasText(row.getEncryptedAppKey()), viewProduct(row, "PLUS"),
                viewProduct(row, "PRO"), viewProduct(row, "ULTRA"), row.getUpdatedAt());
    }

    @Transactional
    public VirtualPaySettingsView save(SaveVirtualPaySettingsRequest request) {
        if (request == null) throw new IllegalArgumentException("虚拟支付配置不能为空");
        VirtualPaySettings existing = mapper.selectById(SETTINGS_ID);
        String encryptedAppKey = existing == null ? null : existing.getEncryptedAppKey();
        if (StringUtils.hasText(request.appKey())) {
            if (!cipher.isReady()) throw new IllegalArgumentException("微信加密密钥未配置");
            encryptedAppKey = cipher.encrypt(request.appKey().trim());
        }
        validate(request, encryptedAppKey);
        VirtualPaySettings row = existing == null ? new VirtualPaySettings() : existing;
        row.setId(SETTINGS_ID);
        row.setEnabled(request.enabled());
        row.setOfferId(trim(request.offerId()));
        row.setEncryptedAppKey(encryptedAppKey);
        apply(row, request.plus(), "PLUS");
        apply(row, request.pro(), "PRO");
        apply(row, request.ultra(), "ULTRA");
        if (existing == null) mapper.insert(row); else mapper.updateById(row);
        return adminView();
    }

    /** 支付链路读取的可信配置，AppKey 仅在服务端内存短暂存在。 */
    public ResolvedSettings resolve() {
        VirtualPaySettings row = mapper.selectById(SETTINGS_ID);
        if (row == null || !Boolean.TRUE.equals(row.getEnabled())
                || !StringUtils.hasText(row.getOfferId()) || !StringUtils.hasText(row.getEncryptedAppKey())) {
            return null;
        }
        return new ResolvedSettings(row.getOfferId(), cipher.decrypt(row.getEncryptedAppKey()), List.of(
                product(row, "PLUS"), product(row, "PRO"), product(row, "ULTRA")));
    }

    private void validate(SaveVirtualPaySettingsRequest request, String encryptedAppKey) {
        if (!request.enabled()) return;
        if (!StringUtils.hasText(request.offerId())) throw new IllegalArgumentException("OfferID 不能为空");
        if (!StringUtils.hasText(encryptedAppKey)) throw new IllegalArgumentException("AppKey 不能为空");
        for (SaveVirtualPaySettingsRequest.Product product : Arrays.asList(
                request.plus(), request.pro(), request.ultra())) {
            if (product == null || !StringUtils.hasText(product.productId())
                    || product.priceCents() <= 0 || product.durationDays() <= 0) {
                throw new IllegalArgumentException("PLUS、PRO、ULTRA 商品配置必须完整");
            }
        }
    }

    private void apply(VirtualPaySettings row, SaveVirtualPaySettingsRequest.Product product, String tier) {
        String id = product == null ? "" : trim(product.productId());
        int price = product == null ? 0 : product.priceCents();
        int days = product == null ? 30 : product.durationDays();
        switch (tier) {
            case "PLUS" -> { row.setPlusProductId(id); row.setPlusPriceCents(price); row.setPlusDurationDays(days); }
            case "PRO" -> { row.setProProductId(id); row.setProPriceCents(price); row.setProDurationDays(days); }
            case "ULTRA" -> { row.setUltraProductId(id); row.setUltraPriceCents(price); row.setUltraDurationDays(days); }
            default -> throw new IllegalArgumentException("未知订阅等级");
        }
    }

    private Product product(VirtualPaySettings row, String tier) {
        return switch (tier) {
            case "PLUS" -> new Product(row.getPlusProductId(), "PLUS月度订阅", tier,
                    value(row.getPlusPriceCents()), value(row.getPlusDurationDays()));
            case "PRO" -> new Product(row.getProProductId(), "PRO月度订阅", tier,
                    value(row.getProPriceCents()), value(row.getProDurationDays()));
            case "ULTRA" -> new Product(row.getUltraProductId(), "ULTRA月度订阅", tier,
                    value(row.getUltraPriceCents()), value(row.getUltraDurationDays()));
            default -> throw new IllegalArgumentException("未知订阅等级");
        };
    }

    private VirtualPaySettingsView.Product viewProduct(VirtualPaySettings row, String tier) {
        Product p = product(row, tier);
        return new VirtualPaySettingsView.Product(p.productId(), p.goodsPrice(), p.durationDays());
    }

    private VirtualPaySettingsView emptyView() {
        VirtualPaySettingsView.Product empty = new VirtualPaySettingsView.Product("", 0, 30);
        return new VirtualPaySettingsView(false, "", false, empty, empty, empty, null);
    }

    private static int value(Integer value) { return value == null ? 0 : value; }
    private static String trim(String value) { return value == null ? "" : value.trim(); }

    public record ResolvedSettings(String offerId, String appKey, List<Product> products) {
    }

    public record Product(String productId, String name, String tier, int goodsPrice, int durationDays) {
    }
}
