package com.xander.lab.identity.application;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 保留策略事务边界测试。
 *
 * <p>这个类的存在价值就是"一批一事务"，而这件事<b>只能</b>靠注解反射来守：
 * 纯单测拿不到 Spring 代理，而注解被删掉或挪到循环外面时，功能测试全绿 ——
 * 症状只在生产高峰出现（长事务持锁、复制延迟、refresh 请求排队）。</p>
 */
class AuthRefreshRetentionServiceTest {

    @Test
    void bothBatchMethodsAreTransactional() throws NoSuchMethodException {
        for (String name : new String[]{"deleteExpiredHistory", "clearExpiredCiphertext"}) {
            Method method = AuthRefreshRetentionService.class
                    .getMethod(name, LocalDateTime.class, int.class);

            assertThat(method.getAnnotation(Transactional.class))
                    .as("%s 必须带 @Transactional，否则「每批独立事务」无声失效", name)
                    .isNotNull();
        }
    }

    /**
     * 两个清理任务共用本类，所以它们的事务语义不可能分叉。
     *
     * <p>如果哪一天有人给其中一个任务单独写了一套批次方法，这条会失败 ——
     * 那正是"两条关闭路径"式缺陷的开端。</p>
     */
    @Test
    void classIsSharedByBothCleanupTasks() {
        assertThat(AuthRefreshRetentionService.class.getDeclaredMethods())
                .extracting(Method::getName)
                .contains("deleteExpiredHistory", "clearExpiredCiphertext");
    }
}
