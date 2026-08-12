package com.xander.lab.billing.application;

import com.xander.lab.billing.domain.PointAccount;
import com.xander.lab.billing.domain.PointsLedger;
import com.xander.lab.billing.infrastructure.BillingProperties;
import com.xander.lab.billing.infrastructure.PointAccountMapper;
import com.xander.lab.billing.infrastructure.PointsLedgerMapper;
import org.junit.jupiter.api.Test;
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

    @Test
    void chargeFlatInsertsBalanceSnapshotBeforeDeductingAccount() {
        BillingProperties properties = new BillingProperties();
        PointAccountMapper accountMapper = mock(PointAccountMapper.class);
        PointsLedgerMapper ledgerMapper = mock(PointsLedgerMapper.class);
        PointAccount account = new PointAccount();
        account.setUserId(7L);
        account.setBalance(100L);
        when(accountMapper.selectById(7L)).thenReturn(account);
        when(accountMapper.selectForUpdate(7L)).thenReturn(account);
        when(accountMapper.deduct(7L, 15L)).thenReturn(1);

        new BillingService(properties, accountMapper, ledgerMapper)
                .chargeFlat(7L, "blog-agent-run", "run:1", "1", 15L, "博客生成");

        var captor = org.mockito.ArgumentCaptor.forClass(PointsLedger.class);
        verify(ledgerMapper).insert(captor.capture());
        assertEquals(85L, captor.getValue().getBalanceAfter());
        assertNotNull(captor.getValue().getUnitPrice());
        verify(accountMapper).deduct(7L, 15L);
        verify(ledgerMapper, never()).updateById(any());
    }

    @Test
    void duplicateLedgerDoesNotDeductAccountAgain() {
        BillingProperties properties = new BillingProperties();
        PointAccountMapper accountMapper = mock(PointAccountMapper.class);
        PointsLedgerMapper ledgerMapper = mock(PointsLedgerMapper.class);
        PointAccount account = new PointAccount();
        account.setUserId(7L);
        account.setBalance(100L);
        when(accountMapper.selectById(7L)).thenReturn(account);
        when(accountMapper.selectForUpdate(7L)).thenReturn(account);
        doThrow(new DuplicateKeyException("duplicate")).when(ledgerMapper).insert(any());

        new BillingService(properties, accountMapper, ledgerMapper)
                .chargeFlat(7L, "blog-agent-run", "run:1", "1", 15L, "博客生成");

        verify(accountMapper, never()).deduct(any(), any());
        verify(accountMapper, never()).add(any(), any());
    }
}
