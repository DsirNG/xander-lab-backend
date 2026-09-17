package com.xander.lab.billing.application;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xander.lab.payment.api.BillingBiz;
import com.xander.lab.payment.api.BillingPort;
import com.xander.lab.payment.api.TokenUsage;
import com.xander.lab.billing.domain.ModelPrice;
import com.xander.lab.billing.domain.PointAccount;
import com.xander.lab.billing.domain.PointsLedger;
import com.xander.lab.billing.infrastructure.BillingProperties;
import com.xander.lab.billing.infrastructure.ModelPriceMapper;
import com.xander.lab.billing.infrastructure.PointAccountMapper;
import com.xander.lab.billing.infrastructure.PointsLedgerMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BillingServiceTest {

    private final PointAccountMapper accountMapper = mock(PointAccountMapper.class);
    private final PointsLedgerMapper ledgerMapper = mock(PointsLedgerMapper.class);
    private final ModelPriceMapper modelPriceMapper = mock(ModelPriceMapper.class);

    private BillingService newService(ModelPrice dbPrice) {
        when(modelPriceMapper.selectOne(any())).thenReturn(dbPrice);
        return new BillingService(new BillingProperties(), accountMapper, ledgerMapper, modelPriceMapper);
    }

    /** 让账户查询返回指定余额的账户，并放行扣减 SQL。 */
    private PointAccount stubAccount(long balance) {
        PointAccount account = new PointAccount();
        account.setUserId(7L);
        account.setBalance(balance);
        when(accountMapper.selectById(7L)).thenReturn(account);
        when(accountMapper.selectForUpdate(7L)).thenReturn(account);
        when(accountMapper.deduct(any(), any())).thenReturn(1);
        return account;
    }

    @Test
    void chargeFlatInsertsBalanceSnapshotBeforeDeductingAccount() {
        BillingService service = newService(null);
        stubAccount(100_000L);

        service.chargeFlat(7L, "blog-agent-run", "run:1", "1", 15_000L, "博客生成");

        ArgumentCaptor<PointsLedger> captor = ArgumentCaptor.forClass(PointsLedger.class);
        verify(ledgerMapper).insert(captor.capture());
        assertEquals(85_000L, captor.getValue().getBalanceAfter());
        assertNotNull(captor.getValue().getUnitPrice());
        verify(accountMapper).deduct(7L, 15_000L);
        verify(ledgerMapper, never()).updateById(any());
    }

    @Test
    void duplicateLedgerDoesNotDeductAccountAgain() {
        BillingService service = newService(null);
        stubAccount(100_000L);
        doThrow(new DuplicateKeyException("duplicate")).when(ledgerMapper).insert(any());

        service.chargeFlat(7L, "blog-agent-run", "run:1", "1", 15_000L, "博客生成");

        verify(accountMapper, never()).deduct(any(), any());
        verify(accountMapper, never()).add(any(), any());
    }

    @Test
    void chargeTokensUsesDatabasePriceOverYmlDefault() {
        // 1 元 = 10 积分：¥2/M、¥12/M、¥0.2/M 对应 20/120/2 毫分每 1K。
        ModelPrice db = new ModelPrice();
        db.setModel("gpt-5.6-terra");
        db.setInputMilliPer1kToken(20L);
        db.setOutputMilliPer1kToken(120L);
        db.setCachedMilliPer1kToken(2L);
        db.setEnabled(1);

        BillingService service = newService(db);
        stubAccount(1_000_000L);

        // 5036 输入 + 5690 输出 → 5036*20/1000 + 5690*120/1000 = 783.52 毫分 → 784。
        service.chargeTokens(7L, "blog-agent-run", "run:terra", null,
                new TokenUsage(5036, 0, 5690), "gpt-5.6-terra", "博客生成");

        ArgumentCaptor<PointsLedger> captor = ArgumentCaptor.forClass(PointsLedger.class);
        verify(ledgerMapper).insert(captor.capture());
        assertEquals(784L, captor.getValue().getAmount());
    }

    @Test
    void chargeTokensFallsBackToYmlWhenModelNotConfigured() {
        BillingService service = newService(null);
        stubAccount(1_000_000L);

        // 未配置模型回退 yml 默认价 50/200：5036*50/1000 + 5690*200/1000 = 1389.8 毫分 → 1390。
        service.chargeTokens(7L, "blog-agent-run", "run:unknown", null,
                new TokenUsage(5036, 0, 5690), "unknown-model", "博客生成");

        ArgumentCaptor<PointsLedger> captor = ArgumentCaptor.forClass(PointsLedger.class);
        verify(ledgerMapper).insert(captor.capture());
        assertEquals(1390L, captor.getValue().getAmount());
    }

    @Test
    void illustrationWithoutUsageIsRejectedInsteadOfUsingFlatPrice() {
        BillingService service = newService(null);

        assertThatThrownBy(() -> service.chargeTokens(7L, BillingBiz.BLOG_AGENT_ILLUSTRATION,
                "illustration:1:1", "task:1", TokenUsage.NONE, "gpt-image-2", "知识插图生成"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("图片模型未返回 token 用量，无法按 token 计费");
    }

    @Test
    void cachedInputTokensAreNotChargedAgainAtTheFullInputRate() {
        ModelPrice db = new ModelPrice();
        db.setModel("gpt-5.6-terra");
        db.setInputMilliPer1kToken(20L);
        db.setOutputMilliPer1kToken(120L);
        db.setCachedMilliPer1kToken(2L);
        db.setEnabled(1);
        BillingService service = newService(db);
        stubAccount(1_000_000L);

        service.chargeTokens(7L, "agent-turn", "run:cached", null,
                new TokenUsage(1000, 400, 0), "gpt-5.6-terra", "智能体会话");

        ArgumentCaptor<PointsLedger> captor = ArgumentCaptor.forClass(PointsLedger.class);
        verify(ledgerMapper).insert(captor.capture());
        // 600*20/1000 + 400*2/1000 = 12.8 毫分，四舍五入为 13。
        assertEquals(13L, captor.getValue().getAmount());
    }

    /**
     * 契约口径的三个分量必须落到正确的流水列上。
     *
     * <p>⚠️ 这里有一处<b>真实存在的换位</b>，而且编译不会报错：
     * {@code TokenUsage} 的分量顺序是 {@code (input, cached, output)}，
     * 而 {@code recordAndDeduct} 的入参顺序是 {@code (..., input, output, cached, ...)}
     * —— 后两个正好相反。{@code chargeTokens} 里那句
     * {@code recordAndDeduct(..., usage.inputTokens(), usage.outputTokens(), usage.cachedTokens(), ...)}
     * 就是这处对调，写错只会让流水里的输出与缓存两列互换，账单金额照旧、不抛异常。</p>
     *
     * <p>用三个互不相同的值钉住：任意两位换位都会让这条红。</p>
     */
    @Test
    void tokenUsagePositionsLandInTheRightLedgerColumns() {
        BillingService service = newService(null);
        stubAccount(1_000_000L);

        service.chargeTokens(7L, "agent-turn", "run:positions", null,
                new TokenUsage(1000, 400, 2000), "gpt-5.6-terra", "智能体会话");

        ArgumentCaptor<PointsLedger> captor = ArgumentCaptor.forClass(PointsLedger.class);
        verify(ledgerMapper).insert(captor.capture());
        PointsLedger ledger = captor.getValue();
        assertEquals(1000L, ledger.getInputTokens(), "inputTokens 落到 input_tokens 列");
        assertEquals(2000L, ledger.getOutputTokens(), "outputTokens 落到 output_tokens 列");
        assertEquals(400L, ledger.getCachedTokens(), "cachedTokens 落到 cached_tokens 列");
    }

    /**
     * 实现必须真的实现端口。
     *
     * <p>看起来是废话，但"端口抽出来了、实现没接上"编译期抓不到：只要没有任何地方
     * 按端口类型引用它，Spring 照样能注入具体类，而拆分之后 agent 那个进程里
     * 只有端口、没有具体类 —— 那时才炸，且炸在启动期。</p>
     */
    @Test
    void theServiceImplementsTheContractPort() {
        assertTrue(BillingPort.class.isAssignableFrom(BillingService.class));
    }

    @Test
    void ledgerItemsExposeSignedDeltaAndDirection() {
        BillingService service = newService(null);
        when(ledgerMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            Page<PointsLedger> page = new Page<>(1, 20);
            PointsLedger grant = new PointsLedger();
            grant.setId(1L);
            grant.setUserId(7L);
            grant.setBizType(BillingBiz.GRANT_REGISTER);
            grant.setAmount(2_000_000L);
            grant.setBalanceAfter(2_000_000L);
            PointsLedger consume = new PointsLedger();
            consume.setId(2L);
            consume.setUserId(7L);
            consume.setBizType(BillingBiz.BLOG_AGENT_RUN);
            consume.setAmount(7835L);
            consume.setBalanceAfter(1_992_165L);
            page.setRecords(List.of(consume, grant));
            return page;
        });

        BillingService.PointsLedgerPage result = service.pageLedger(7L, 1, 20);

        assertEquals(2, result.records().size());
        // 消耗行：delta 为负、direction=debit；赠送行：delta 为正、direction=credit。
        assertEquals(-7835L, result.records().get(0).delta());
        assertEquals("debit", result.records().get(0).direction());
        assertEquals(7835L, result.records().get(0).amount());
        assertEquals(2_000_000L, result.records().get(1).delta());
        assertEquals("credit", result.records().get(1).direction());
    }
}
