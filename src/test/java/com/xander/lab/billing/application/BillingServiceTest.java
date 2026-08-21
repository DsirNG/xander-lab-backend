package com.xander.lab.billing.application;

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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        // 数据库给 gpt-5.6-terra 配 200/1200/20 毫分每 1K（¥2/M、¥12/M、¥0.2/M）。
        ModelPrice db = new ModelPrice();
        db.setModel("gpt-5.6-terra");
        db.setInputMilliPer1kToken(200L);
        db.setOutputMilliPer1kToken(1200L);
        db.setCachedMilliPer1kToken(20L);
        db.setEnabled(1);

        BillingService service = newService(db);
        stubAccount(1_000_000L);

        // 与线上账单对齐：5036 输入 + 5690 输出 → 5036*200/1000 + 5690*1200/1000 = 7835.2 毫分 → 7835
        service.chargeTokens(7L, "blog-agent-run", "run:terra", null,
                new ModelUsage(5036, 0, 5690), "gpt-5.6-terra", "博客生成");

        ArgumentCaptor<PointsLedger> captor = ArgumentCaptor.forClass(PointsLedger.class);
        verify(ledgerMapper).insert(captor.capture());
        assertEquals(7835L, captor.getValue().getAmount());
    }

    @Test
    void chargeTokensFallsBackToYmlWhenModelNotConfigured() {
        BillingService service = newService(null);
        stubAccount(1_000_000L);

        // 未配置模型回退 yml 默认价 500/2000：5036*500/1000 + 5690*2000/1000 = 13898 毫分
        service.chargeTokens(7L, "blog-agent-run", "run:unknown", null,
                new ModelUsage(5036, 0, 5690), "unknown-model", "博客生成");

        ArgumentCaptor<PointsLedger> captor = ArgumentCaptor.forClass(PointsLedger.class);
        verify(ledgerMapper).insert(captor.capture());
        assertEquals(13898L, captor.getValue().getAmount());
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
