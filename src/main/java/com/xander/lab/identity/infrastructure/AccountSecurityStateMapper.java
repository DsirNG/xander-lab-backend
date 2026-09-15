package com.xander.lab.identity.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.identity.domain.AccountSecurityState;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 账号安全状态 Mapper（V9 §27 / §48 / §70）。
 */
@Mapper
public interface AccountSecurityStateMapper extends BaseMapper<AccountSecurityState> {

    /**
     * 分配下一个全局单调变更游标。
     *
     * <p>{@code LAST_INSERT_ID(expr)} 会把 expr 的值记到本次连接，
     * 因此紧随其后的 {@code LAST_INSERT_ID()} 能取回它 —— 这是把"分配游标"
     * 与"写业务行"放进同一个事务的标准写法。</p>
     */
    @Select("UPDATE security_state_sequence SET seq = LAST_INSERT_ID(seq + 1) WHERE id = 1")
    void allocateChangeSeq();

    /** 取回本连接刚分配的游标值。 */
    @Select("SELECT LAST_INSERT_ID()")
    Long currentChangeSeq();

    /**
     * per_instance reconciler 的增量拉取（V9 §70）。
     *
     * <p>每个实例维护自己的 {@code lastSeenSeq}，不使用全局单例游标 ——
     * 否则先到的实例会把游标推高，其他实例永久漏掉那段区间（V9 §27）。</p>
     */
    @Select("""
            SELECT * FROM account_security_state
             WHERE change_seq > #{cursor}
             ORDER BY change_seq
             LIMIT #{limit}
            """)
    List<AccountSecurityState> pullChangesAfter(@Param("cursor") long cursor,
                                                @Param("limit") int limit);

    /**
     * 全量对账兜底。
     *
     * <p>当实例长时间未启动（游标落后于已清理的历史）或需要自愈时使用，
     * 保证最终收敛。</p>
     */
    @Select("SELECT * FROM account_security_state WHERE disabled = 1")
    List<AccountSecurityState> listDisabled();
}
