package com.xander.lab.knowledgebase.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.xander.lab.knowledgebase.domain.KnowledgeUploadChunk;
import com.xander.lab.knowledgebase.domain.KnowledgeUploadSession;
import com.xander.lab.knowledgebase.domain.UploadSessionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 回收过期的分片上传会话：置 EXPIRED、删分片记录、清本地暂存目录。
 *
 * <p>默认不启用（{@code knowledge-base.reaper-enabled=false}）。它会删本地文件，
 * 该由部署方显式打开，而不是随代码上线就开始动磁盘。</p>
 *
 * <p>每条会话先用一次条件更新「抢」下来（{@code status} 必须仍是 UPLOADING），
 * 抢到了才去删文件。多实例部署时这一步保证同一个会话只被回收一次，
 * 也保证不会把一个刚刚在 complete 里被抢走的会话误删。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "knowledge-base.reaper-enabled", havingValue = "true")
public class KnowledgeUploadSessionReaper {

    private final KnowledgeUploadSessionMapper sessionMapper;
    private final KnowledgeUploadChunkMapper chunkMapper;
    private final ChunkStagingStore stagingStore;
    private final KnowledgeBaseProperties properties;

    @Scheduled(
            fixedDelayString = "${knowledge-base.reaper-interval-ms:3600000}",
            initialDelayString = "${knowledge-base.reaper-initial-delay-ms:60000}"
    )
    @SchedulerLock(name = "knowledge-upload-session-reaper", lockAtMostFor = "PT10M")
    public void reapExpiredSessions() {
        try {
            List<KnowledgeUploadSession> expired = sessionMapper.selectList(
                    new LambdaQueryWrapper<KnowledgeUploadSession>()
                            .eq(KnowledgeUploadSession::getStatus, UploadSessionStatus.UPLOADING.name())
                            .lt(KnowledgeUploadSession::getExpiresAt, LocalDateTime.now())
                            .orderByAsc(KnowledgeUploadSession::getId)
                            .last("LIMIT " + Math.max(1, properties.getReaperBatchSize())));
            if (expired.isEmpty()) {
                return;
            }
            int reaped = 0;
            for (KnowledgeUploadSession session : expired) {
                if (claim(session)) {
                    release(session);
                    reaped++;
                }
            }
            log.info("[知识库] 回收过期上传会话 {}/{} 条", reaped, expired.size());
        } catch (Exception e) {
            // 定时任务不能因为一次异常就停掉，下一轮还要继续跑。
            log.warn("[知识库] 过期上传会话回收失败：{}", e.getMessage());
        }
    }

    private boolean claim(KnowledgeUploadSession session) {
        return sessionMapper.update(null, new LambdaUpdateWrapper<KnowledgeUploadSession>()
                .eq(KnowledgeUploadSession::getId, session.getId())
                .eq(KnowledgeUploadSession::getStatus, UploadSessionStatus.UPLOADING.name())
                .set(KnowledgeUploadSession::getStatus, UploadSessionStatus.EXPIRED.name())
                // 终态释放去重键，同一个文件日后还能重新上传。
                .set(KnowledgeUploadSession::getIdempotencyKey, null)) == 1;
    }

    private void release(KnowledgeUploadSession session) {
        chunkMapper.delete(new LambdaQueryWrapper<KnowledgeUploadChunk>()
                .eq(KnowledgeUploadChunk::getSessionId, session.getId()));
        stagingStore.discardQuietly(session.getUploadId());
    }
}
