package com.xander.lab.billing.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xander.lab.billing.domain.BillingBiz;
import com.xander.lab.billing.domain.ModelPrice;
import com.xander.lab.billing.domain.PointAccount;
import com.xander.lab.billing.domain.PointsLedger;
import com.xander.lab.billing.infrastructure.BillingProperties;
import com.xander.lab.billing.infrastructure.ModelPriceMapper;
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
    private final ModelPriceMapper modelPriceMapper;

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
        long points = tokenToPoints(usage, model);
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

    /** 分页返回当前用户的流水视图：delta 带符号（收入为正、消耗为负），供端上直接渲染 +/-。 */
    public PointsLedgerPage pageLedger(Long userId, int page, int size) {
        Page<PointsLedger> paged = ledgerMapper.selectPage(
                new Page<>(Math.max(1, page), Math.min(Math.max(1, size), 100)),
                new LambdaQueryWrapper<PointsLedger>()
                        .eq(PointsLedger::getUserId, userId)
                        .orderByDesc(PointsLedger::getId));
        List<PointsLedgerItem> items = paged.getRecords().stream()
                .map(BillingService::toItem)
                .toList();
        return new PointsLedgerPage(items, paged.getTotal());
    }

    private static PointsLedgerItem toItem(PointsLedger ledger) {
        boolean credit = isCreditBiz(ledger.getBizType());
        long amount = ledger.getAmount() == null ? 0L : ledger.getAmount();
        return new PointsLedgerItem(
                ledger.getId(), ledger.getBizType(), ledger.getBizKey(), ledger.getBizRef(),
                credit ? amount : -amount, credit ? "credit" : "debit",
                amount, ledger.getBalanceAfter(), ledger.getUnitPrice(),
                ledger.getModel(), ledger.getInputTokens(), ledger.getOutputTokens(),
                ledger.getCachedTokens(), ledger.getRemark(), ledger.getCreatedAt());
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
                "unit", "milli-point",
                "balance", balance,
                "consumedToday", consumedToday,
                "enablePreCheck", properties.isEnabled() && balance < properties.getPreCheckMinBalance());
    }

    public Map<String, Object> priceList() {
        Map<String, Object> modelPrices = new java.util.LinkedHashMap<>();
        // 数据库价优先展示；yml 里配置但未入库的模型也一并列出，便于对照。
        listModelPrices().forEach(p -> modelPrices.put(p.model(), Map.of(
                "source", "db",
                "enabled", p.enabled(),
                "inputMilliPer1kToken", p.inputMilliPer1kToken(),
                "outputMilliPer1kToken", p.outputMilliPer1kToken(),
                "cachedMilliPer1kToken", p.cachedMilliPer1kToken(),
                "cachedWriteMilliPer1kToken", p.cachedWriteMilliPer1kToken())));
        properties.getModels().forEach((model, modelPrice) -> {
            if (modelPrices.containsKey(model)) return;
            BillingProperties.Price p = modelPrice.getPrice();
            modelPrices.put(model, Map.of(
                    "source", "yml",
                    "inputMilliPer1kToken", p.getInputMilliPer1kToken(),
                    "outputMilliPer1kToken", p.getOutputMilliPer1kToken(),
                    "cachedMilliPer1kToken", p.getCachedMilliPer1kToken(),
                    "cachedWriteMilliPer1kToken", modelPrice.getCachedWriteMilliPer1kToken()));
        });
        return Map.of(
                "unit", "milli-point",
                "grantRegister", properties.getGrantRegister(),
                "preCheckMinBalance", properties.getPreCheckMinBalance(),
                "token", Map.of(
                        "inputMilliPer1kToken", properties.getPrice().getInputMilliPer1kToken(),
                        "outputMilliPer1kToken", properties.getPrice().getOutputMilliPer1kToken(),
                        "cachedMilliPer1kToken", properties.getPrice().getCachedMilliPer1kToken()),
                "models", modelPrices,
                "flat", properties.getFlat());
    }

    /** 全部模型定价（管理员视图），按模型名排序。 */
    public List<ModelPriceView> listModelPrices() {
        return modelPriceMapper.selectList(new LambdaQueryWrapper<ModelPrice>()
                        .orderByAsc(ModelPrice::getModel))
                .stream()
                .map(BillingService::toView)
                .toList();
    }

    /**
     * 新增或更新模型定价（按 model 幂等 upsert）。
     * 并发插入撞唯一键时回退为更新，保证最终一致。
     */
    @Transactional
    public ModelPriceView upsertModelPrice(String model, long inputMilli, long outputMilli,
                                           long cachedMilli, long cachedWriteMilli,
                                           boolean enabled, String remark) {
        if (model == null || model.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模型名称不能为空");
        }
        if (inputMilli < 0 || outputMilli < 0 || cachedMilli < 0 || cachedWriteMilli < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单价不能为负数");
        }
        String trimmed = model.trim();
        ModelPrice existing = modelPriceMapper.selectOne(new LambdaQueryWrapper<ModelPrice>()
                .eq(ModelPrice::getModel, trimmed)
                .last("LIMIT 1"));
        ModelPrice entity = existing == null ? new ModelPrice() : existing;
        entity.setModel(trimmed);
        entity.setInputMilliPer1kToken(inputMilli);
        entity.setOutputMilliPer1kToken(outputMilli);
        entity.setCachedMilliPer1kToken(cachedMilli);
        entity.setCachedWriteMilliPer1kToken(cachedWriteMilli);
        entity.setEnabled(enabled ? 1 : 0);
        entity.setRemark(remark);
        if (existing == null) {
            try {
                modelPriceMapper.insert(entity);
            } catch (DuplicateKeyException duplicate) {
                // 并发插入同一模型：转为更新已有行。
                ModelPrice winner = modelPriceMapper.selectOne(new LambdaQueryWrapper<ModelPrice>()
                        .eq(ModelPrice::getModel, trimmed)
                        .last("LIMIT 1"));
                if (winner == null) throw duplicate;
                entity.setId(winner.getId());
                modelPriceMapper.updateById(entity);
            }
        } else {
            modelPriceMapper.updateById(entity);
        }
        return toView(entity);
    }

    /** 删除模型定价；删除后该模型计费回退 application.yml 默认价。 */
    @Transactional
    public void deleteModelPrice(String model) {
        if (model == null || model.isBlank()) return;
        modelPriceMapper.delete(new LambdaQueryWrapper<ModelPrice>()
                .eq(ModelPrice::getModel, model.trim()));
    }

    private static ModelPriceView toView(ModelPrice entity) {
        return new ModelPriceView(entity.getModel(), entity.getInputMilliPer1kToken(),
                entity.getOutputMilliPer1kToken(), entity.getCachedMilliPer1kToken(),
                entity.getCachedWriteMilliPer1kToken(), entity.getEnabled() != null && entity.getEnabled() == 1,
                entity.getRemark(), entity.getUpdatedAt());
    }

    /** 模型定价管理视图。 */
    public record ModelPriceView(String model, long inputMilliPer1kToken, long outputMilliPer1kToken,
                                 long cachedMilliPer1kToken, long cachedWriteMilliPer1kToken,
                                 boolean enabled, String remark, LocalDateTime updatedAt) {
    }

    /** 注册赠送（幂等：同一 bizKey 只赠送一次）。 */
    @Transactional
    public void grantRegister(Long userId, String bizKey) {
        if (!properties.isEnabled() || userId == null || properties.getGrantRegister() <= 0) return;
        recordAndDeduct(userId, BillingBiz.GRANT_REGISTER, bizKey, null,
                properties.getGrantRegister(), null, null, null, null, "注册赠送");
    }

    /** 账号合并：把来源账号积分余额整体转入目标账号（产生一笔转入流水），并删除来源账户行。
     *  幂等：来源账户不存在或余额为 0 时直接返回；bizKey 保证同一合并只入账一次。 */
    @Transactional
    public void transferBalance(Long fromUserId, Long toUserId, String bizKey, String remark) {
        if (!properties.isEnabled() || fromUserId == null || toUserId == null || fromUserId.equals(toUserId)) {
            return;
        }
        PointAccount from = accountMapper.selectById(fromUserId);
        if (from == null || from.getBalance() == 0) {
            return;
        }
        recordAndDeduct(toUserId, BillingBiz.MERGE_CREDIT, bizKey, null, from.getBalance(),
                null, null, null, null, remark == null ? "账号合并转入" : remark);
        accountMapper.deleteById(fromUserId);
    }

    /** 按单价折算毫分：单价为「毫分 / 每 1K token」，tokens×milli 即毫分，四舍五入后至少 1 毫分。 */
    private long tokenToPoints(ModelUsage usage, String model) {
        BillingProperties.Price price = resolvePrice(model);
        long input = Math.max(0L, usage.inputTokens());
        long output = Math.max(0L, usage.outputTokens());
        long cached = Math.min(input, Math.max(0L, usage.cachedTokens()));
        long uncachedInput = input - cached;
        double milliPoints = uncachedInput / 1000.0 * price.getInputMilliPer1kToken()
                + output / 1000.0 * price.getOutputMilliPer1kToken()
                + cached / 1000.0 * price.getCachedMilliPer1kToken();
        return Math.max(1L, Math.round(milliPoints));
    }

    /**
     * 解析模型单价：数据库 billing_model_price（enabled=1）优先，未配置或停用时回退 yml 默认价。
     * 计费频率与模型调用成功次数同量级，直接查库即可，无需缓存。
     */
    private BillingProperties.Price resolvePrice(String model) {
        if (model != null && !model.isBlank()) {
            ModelPrice db = modelPriceMapper.selectOne(new LambdaQueryWrapper<ModelPrice>()
                    .eq(ModelPrice::getModel, model)
                    .eq(ModelPrice::getEnabled, 1)
                    .last("LIMIT 1"));
            if (db != null) {
                BillingProperties.Price price = new BillingProperties.Price();
                price.setInputMilliPer1kToken(db.getInputMilliPer1kToken());
                price.setOutputMilliPer1kToken(db.getOutputMilliPer1kToken());
                price.setCachedMilliPer1kToken(db.getCachedMilliPer1kToken());
                return price;
            }
        }
        return properties.priceForModel(model);
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
        boolean credit = isCreditBiz(bizType);
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
        BillingProperties.Price price = resolvePrice(model);
        return price.getInputMilliPer1kToken()
                + "/" + price.getOutputMilliPer1kToken()
                + "/" + price.getCachedMilliPer1kToken()
                + (model == null ? "" : " " + model);
    }

    /** 收入类业务（余额增加）：注册赠送与账号合并转入，其余均为消耗。 */
    private static boolean isCreditBiz(String bizType) {
        return BillingBiz.GRANT_REGISTER.equals(bizType) || BillingBiz.MERGE_CREDIT.equals(bizType);
    }

    private void ensureAccount(Long userId) {
        if (accountMapper.selectById(userId) != null) return;
        PointAccount account = new PointAccount();
        account.setUserId(userId);
        account.setBalance(0L);
        try {
            accountMapper.insert(account);
        } catch (DuplicateKeyException duplicate) {
            // 并发建户时另一请求已插入，忽略。
        }
    }

    /** 流水分页视图：amount 恒为正数，delta 带符号（credit=+amount / debit=-amount），单位毫分。 */
    public record PointsLedgerItem(Long id, String bizType, String bizKey, String bizRef,
                                   long delta, String direction, long amount, Long balanceAfter,
                                   String unitPrice, String model, Long inputTokens,
                                   Long outputTokens, Long cachedTokens, String remark,
                                   LocalDateTime createdAt) {
    }

    public record PointsLedgerPage(List<PointsLedgerItem> records, long total) {
    }
}
