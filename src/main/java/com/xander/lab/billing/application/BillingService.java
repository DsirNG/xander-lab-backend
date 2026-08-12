package com.xander.lab.billing.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xander.lab.billing.domain.BillingBiz;
import com.xander.lab.billing.domain.PointAccount;
import com.xander.lab.billing.domain.PointsLedger;
import com.xander.lab.billing.infrastructure.BillingProperties;
import com.xander.lab.billing.infrastructure.PointAccountMapper;
import com.xander.lab.billing.infrastructure.PointsLedgerMapper;
import com.xander.lab.common.ModelUsage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 积分账本应用服务。
 *
 * <p>对外只暴露三个端口：{@link #chargeTokens}（token 精确计费）、
 * {@link #chargeFlat}（一口价）、{@link #ensureAvailable}（发起消耗前的余额预检）。
 * 计费发生在后台任务的成功哨点，与外部模型调用解耦，因此无需退款链路。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingService {

    private final BillingProperties properties;
    private final PointAccountMapper accountMapper;
    private final PointsLedgerMapper ledgerMapper;

    /** token 精确计费：按 usage 折成积分后扣减。网关未回 usage 时退回一口价。 */
    @Transactional
    public void chargeTokens(Long userId, String bizType, String bizKey, String bizRef,
                             ModelUsage usage, String model, String remark) {
        if (!properties.isEnabled() || userId == null || usage == null || usage.isEmpty()) {
            if (usage != null && usage.isEmpty()) {
                long fallback = flatPrice(bizType);
                if (fallback > 0) {
                    chargeFlat(userId, bizType, bizKey, bizRef, fallback, remark + "（网关未返回用量，按一口价）");
                }
            }
            return;
        }
        long points = tokenToPoints(usage);
        if (points <= 0) points = 1;
        recordAndDeduct(userId, bizType, bizKey, bizRef, points, model,
                usage.inputTokens(), usage.outputTokens(), usage.cachedTokens(), remark);
    }

    /** 一口价计费（插图 / 邮件 / CSDN 发布等无 token 计量项）。 */
    @Transactional
    public void chargeFlat(Long userId, String bizType, String bizKey, String bizRef,
                           long points, String remark) {
        if (!properties.isEnabled() || userId == null || points <= 0) return;
        recordAndDeduct(userId, bizType, bizKey, bizRef, points, null,
                null, null, null, remark);
    }

    /** 余额预检：低于阈值抛 402，由 GlobalExceptionHandler 统一映射。 */
    public void ensureAvailable(Long userId) {
        if (!properties.isEnabled() || userId == null) return;
        if (balance(userId) < properties.getPreCheckMinBalance()) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "积分不足，请先获取积分");
        }
    }

    public long balance(Long userId) {
        PointAccount account = accountMapper.selectById(userId);
        return account == null ? 0L : account.getBalance();
    }

    public PointsLedgerPage pageLedger(Long userId, int page, int size) {
        Page<PointsLedger> paged = ledgerMapper.selectPage(
                new Page<>(Math.max(1, page), Math.min(Math.max(1, size), 100)),
                new LambdaQueryWrapper<PointsLedger>()
                        .eq(PointsLedger::getUserId, userId)
                        .orderByDesc(PointsLedger::getId));
        return new PointsLedgerPage(paged.getRecords(), paged.getTotal());
    }

    /** 当前余额与近日消耗汇总，供前端积分面板展示。 */
    public Map<String, Object> overview(Long userId) {
        long balance = balance(userId);
        Long consumedToday = ledgerMapper.selectList(new LambdaQueryWrapper<PointsLedger>()
                        .eq(PointsLedger::getUserId, userId)
                        .ne(PointsLedger::getBizType, BillingBiz.GRANT_REGISTER)
                        .ge(PointsLedger::getCreatedAt, LocalDateTime.now().toLocalDate().atStartOfDay()))
                .stream().mapToLong(PointsLedger::getAmount).sum();
        return Map.of(
                "balance", balance,
                "consumedToday", consumedToday,
                "enablePreCheck", properties.isEnabled() && balance < properties.getPreCheckMinBalance());
    }

    public Map<String, Object> priceList() {
        return Map.of(
                "grantRegister", properties.getGrantRegister(),
                "preCheckMinBalance", properties.getPreCheckMinBalance(),
                "token", Map.of(
                        "inputMilliPer1kToken", properties.getPrice().getInputMilliPer1kToken(),
                        "outputMilliPer1kToken", properties.getPrice().getOutputMilliPer1kToken(),
                        "cachedMilliPer1kToken", properties.getPrice().getCachedMilliPer1kToken()),
                "flat", properties.getFlat());
    }

    /** 注册赠送（幂等：同一 bizKey 只赠送一次）。 */
    @Transactional
    public void grantRegister(Long userId, String bizKey) {
        if (!properties.isEnabled() || userId == null || properties.getGrantRegister() <= 0) return;
        recordAndDeduct(userId, BillingBiz.GRANT_REGISTER, bizKey, null,
                properties.getGrantRegister(), null, null, null, null, "注册赠送");
    }

    /** 按单价折算积分：input/output/cached 分开计费，四舍五入后至少 1 分。 */
    private long tokenToPoints(ModelUsage usage) {
        BillingProperties.Price price = properties.getPrice();
        long input = Math.max(0L, usage.inputTokens());
        long output = Math.max(0L, usage.outputTokens());
        long cached = Math.max(0L, usage.cachedTokens());
        double inputPoints = input / 1000.0 * price.getInputMilliPer1kToken();
        double outputPoints = output / 1000.0 * price.getOutputMilliPer1kToken();
        double cachedPoints = cached / 1000.0 * price.getCachedMilliPer1kToken();
        return Math.max(1L, Math.round(inputPoints + outputPoints + cachedPoints));
    }

    private long flatPrice(String bizType) {
        return properties.flatPoints(bizType);
    }

    /** 一口价目录查询，供无 token 计量项的调用点自行决定扣费金额。 */
    public long flatPoints(String key) {
        return properties.flatPoints(key);
    }

    private void recordAndDeduct(Long userId, String bizType, String bizKey, String bizRef,
                                 long points, String model, Long input, Long output, Long cached,
                                 String remark) {
        ensureAccount(userId);
        // 串行化同一账户的变更，使流水中的余额快照与实际账户余额严格一致。
        PointAccount account = accountMapper.selectForUpdate(userId);
        if (account == null) {
            throw new IllegalStateException("积分账户不存在");
        }
        boolean credit = BillingBiz.GRANT_REGISTER.equals(bizType);
        long after = credit ? account.getBalance() + points : account.getBalance() - points;

        PointsLedger ledger = new PointsLedger();
        ledger.setUserId(userId);
        ledger.setBizType(bizType);
        ledger.setBizKey(bizKey);
        ledger.setBizRef(bizRef);
        ledger.setAmount(points);
        ledger.setBalanceAfter(after);
        ledger.setUnitPrice(unitPrice(model));
        ledger.setModel(model);
        ledger.setInputTokens(input);
        ledger.setOutputTokens(output);
        ledger.setCachedTokens(cached);
        ledger.setRemark(remark);
        ledger.setCreatedAt(LocalDateTime.now());
        try {
            ledgerMapper.insert(ledger);
        } catch (DuplicateKeyException duplicate) {
            // 同一 biz_key 已计费（幂等重试或并发），直接忽略，避免重复扣费。
            log.debug("billing ledger duplicate ignored: type={} key={}", bizType, bizKey);
            return;
        }
        int updated = credit
                ? accountMapper.add(userId, points)
                : accountMapper.deduct(userId, points);
        if (updated != 1) {
            throw new IllegalStateException("积分账户更新失败");
        }
    }

    private String unitPrice(String model) {
        return properties.getPrice().getInputMilliPer1kToken()
                + "/" + properties.getPrice().getOutputMilliPer1kToken()
                + "/" + properties.getPrice().getCachedMilliPer1kToken()
                + (model == null ? "" : " " + model);
    }

    private void ensureAccount(Long userId) {
        if (accountMapper.selectById(userId) != null) return;
        PointAccount account = new PointAccount();
        account.setUserId(userId);
        account.setBalance(0L);
        account.setCreatedAt(LocalDateTime.now());
        account.setUpdatedAt(LocalDateTime.now());
        try {
            accountMapper.insert(account);
        } catch (DuplicateKeyException duplicate) {
            // 并发建户时另一请求已插入，忽略。
        }
    }

    public record PointsLedgerPage(List<PointsLedger> records, long total) {
    }
}
