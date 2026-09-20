package com.xander.lab.identity.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.identity.domain.AccountSecurityState;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 账号安全状态 Mapper。
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
     * 幂等写入账号安全状态。
     *
     * <p>两条约束写死在 SQL 里，不能挪到 Java 侧：</p>
     *
     * <ol>
     *   <li>{@code change_seq} 必须显式写入。用 AUTO_INCREMENT 会让<b>更新路径</b>
     *       不推进该列，于是 per_instance reconciler 的 {@code change_seq > cursor}
     *       增量拉取会静默漏掉这次封禁 —— 而实例照样收敛到"没有变化"，
     *       是一个不会报错的失败。</li>
     *   <li>{@code security_version} 用 SQL 侧 {@code + 1}，不做 Java 侧读-改-写。
     *       两个管理员并发操作同一账号时，读-改-写会让两次变更算出同一个版本号，
     *       于是任何"按版本号失效"的缓存都少失效一次。</li>
     * </ol>
     *
     * <p>{@code disabled_at} 用条件赋值：解封时<b>保留</b>最近一次封禁时间。
     * 它是 {@code admin_ban_*} 延迟指标的分母，清空会让"封禁过又被解封"的账号
     * 从指标里消失。</p>
     *
     * @param disabled 1=封禁，0=正常
     * @return 受影响行数（1=新增，2=更新，MySQL ODKU 语义）
     */
    @Insert("""
            INSERT INTO account_security_state
                (account_id, disabled, security_version, change_seq, disabled_at, reason)
            VALUES (#{accountId}, #{disabled}, 1, #{changeSeq}, #{disabledAt}, #{reason})
            ON DUPLICATE KEY UPDATE
                disabled         = VALUES(disabled),
                security_version = security_version + 1,
                change_seq       = VALUES(change_seq),
                disabled_at      = IF(VALUES(disabled) = 1, VALUES(disabled_at), disabled_at),
                reason           = VALUES(reason)
            """)
    int upsertState(@Param("accountId") Long accountId,
                    @Param("disabled") int disabled,
                    @Param("changeSeq") long changeSeq,
                    @Param("disabledAt") LocalDateTime disabledAt,
                    @Param("reason") String reason);

    /**
     * per_instance reconciler 的增量拉取。
     *
     * <p>每个实例维护自己的 {@code lastSeenSeq}，不使用全局单例游标 ——
     * 否则先到的实例会把游标推高，其他实例永久漏掉那段区间。</p>
     */
    @Select("""
            SELECT * FROM account_security_state
             WHERE change_seq > #{cursor}
             ORDER BY change_seq
             LIMIT #{limit}
            """)
    List<AccountSecurityState> pullChangesAfter(@Param("cursor") long cursor,
                                                @Param("limit") int limit);

    /** Gateway 启动快照与低频全量对账使用，保留已解封账号的版本闸门。 */
    @Select("SELECT * FROM account_security_state")
    List<AccountSecurityState> listAll();

    /** 当前全局安全游标，仅用于对账滞后观测，不用于推进 Gateway 游标。 */
    @Select("SELECT seq FROM security_state_sequence WHERE id = 1")
    Long currentSecurityWatermark();

    /**
     * 全量对账兜底。
     *
     * <p>当实例长时间未启动（游标落后于已清理的历史）或需要自愈时使用，
     * 保证最终收敛。</p>
     */
    @Select("SELECT * FROM account_security_state WHERE disabled = 1")
    List<AccountSecurityState> listDisabled();
}
