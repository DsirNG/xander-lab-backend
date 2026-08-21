package com.xander.lab.billing.application;

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
        stubAccount(100L);

        service.chargeFlat(7L, "blog-agent-run", "run:1", "1", 15L, "博客生成");

        ArgumentCaptor<PointsLedger> captor = ArgumentCaptor.forClass(PointsLedger.class);
        verify(ledgerMapper).insert(captor.capture());
        assertEquals(85L, captor.getValue().getBalanceAfter());
        assertNotNull(captor.getValue().getUnitPrice());
        verify(accountMapper).deduct(7L, 15L);
        verify(ledgerMapper, never()).updateById(any());
    }

    @Test
    void duplicateLedgerDoesNotDeductAccountAgain() {
        BillingService service = newService(null);
        stubAccount(100L);
        doThrow(new DuplicateKeyException("duplicate")).when(ledgerMapper).insert(any());

        service.chargeFlat(7L, "blog-agent-run", "run:1", "1", 15L, "博客生成");

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
        stubAccount(1000L);

        // 与线上账单对齐：5036 输入 + 5690 输出 → round((5036*200 + 5690*1200)/1e6) = round(7.8352) = 8 分
        service.chargeTokens(7L, "blog-agent-run", "run:terra", null,
                new ModelUsage(5036, 0, 5690), "gpt-5.6-terra", "博客生成");

        ArgumentCaptor<PointsLedger> captor = ArgumentCaptor.forClass(PointsLedger.class);
        verify(ledgerMapper).insert(captor.capture());
        assertEquals(8L, captor.getValue().getAmount());
    }

    @Test
    void chargeTokensFallsBackToYmlWhenModelNotConfigured() {
        BillingService service = newService(null);
        stubAccount(1000L);

        // 未配置模型回退 yml 默认价 500/2000：round((5036*500 + 5690*2000)/1e6) = round(13.898) = 14 分
        service.chargeTokens(7L, "blog-agent-run", "run:unknown", null,
                new ModelUsage(5036, 0, 5690), "unknown-model", "博客生成");

        ArgumentCaptor<PointsLedger> captor = ArgumentCaptor.forClass(PointsLedger.class);
        verify(ledgerMapper).insert(captor.capture());
        assertEquals(14L, captor.getValue().getAmount());
    }
}
