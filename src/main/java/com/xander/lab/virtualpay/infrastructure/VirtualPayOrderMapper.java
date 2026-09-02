package com.xander.lab.virtualpay.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.virtualpay.domain.VirtualPayOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 虚拟支付订单持久化，状态更新均带前置状态以抵御重复通知和并发查单。 */
@Mapper
public interface VirtualPayOrderMapper extends BaseMapper<VirtualPayOrder> {

    @Update("""
            UPDATE virtual_pay_order
               SET wx_order_id = COALESCE(wx_order_id, #{wxOrderId}),
                   status = 'DELIVERED', paid_at = COALESCE(paid_at, NOW()),
                   delivered_at = NOW(), updated_at = NOW()
             WHERE id = #{id} AND status = 'PENDING'
            """)
    int markDelivered(@Param("id") Long id, @Param("wxOrderId") String wxOrderId);
}
