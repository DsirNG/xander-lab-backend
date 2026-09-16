package com.xander.lab.knowledgebase.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.xander.lab.knowledgebase.api.InitUploadRequest;
import com.xander.lab.knowledgebase.api.KnowledgeFileView;
import com.xander.lab.knowledgebase.api.KnowledgeUploadSessionView;
import com.xander.lab.knowledgebase.domain.KnowledgeFile;
import com.xander.lab.knowledgebase.domain.KnowledgeUploadChunk;
import com.xander.lab.knowledgebase.domain.KnowledgeUploadSession;
import com.xander.lab.knowledgebase.domain.UploadSessionStatus;
import com.xander.lab.knowledgebase.infrastructure.ChunkStagingStore;
import com.xander.lab.knowledgebase.infrastructure.KnowledgeBaseProperties;
import com.xander.lab.knowledgebase.infrastructure.KnowledgeFileMapper;
import com.xander.lab.knowledgebase.infrastructure.KnowledgeUploadChunkMapper;
import com.xander.lab.knowledgebase.infrastructure.KnowledgeUploadSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 分片上传与断点续传。
 *
 * <p>流程是 {@code init → uploadChunk×N → complete}，中间断线只需重新 {@code init}（或 {@code status}）
 * 拿到 {@code missingChunks} 再补传缺的那几片，已传的分片不必重来。</p>
 *
 * <p>三条不可让客户端说话的规则：{@code totalChunks} 由服务端按 {@code sizeBytes / chunkSize} 算；
 * 每片的期望大小由服务端推出来并在落盘后校验；整文件哈希在合并后重算并与客户端声明值比对。
 * 只信客户端的话，一个坏掉的重传就能悄悄产生一个内容错误的文件。</p>
 *
 * <p>合并入库走的是 {@link KnowledgeFileService#store}，与表单直传完全同一条路径，
 * 因此两种上传方式在库里长得一模一样，知识化行为也一致。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeUploadService {

    /** 客户端没给建议值时采用的分片大小。 */
    private static final int DEFAULT_CHUNK_SIZE = 5 * 1024 * 1024;
    /** 分片数量上限，防止客户端把 chunkSize 报得极小从而造出海量分片记录。 */
    private static final int MAX_TOTAL_CHUNKS = 10_000;

    private final KnowledgeUploadSessionMapper sessionMapper;
    private final KnowledgeUploadChunkMapper chunkMapper;
    private final KnowledgeFileMapper fileMapper;
    private final KnowledgeFileService fileService;
    private final ChunkStagingStore stagingStore;
    private final KnowledgeBaseProperties properties;

    /**
     * 开启（或复用）一次分片上传会话。
     *
     * <p>断点续传就在这里成立：同一用户、同一目标文件夹、同一内容哈希、同一大小算出同一个
     * {@code idempotencyKey}，命中未过期的活跃会话就直接把它连同 {@code missingChunks} 返回，
     * 客户端接着传缺的分片即可。因此客户端断线后不需要自己记账，重新 init 就能续上。</p>
     */
    @Transactional
    public KnowledgeUploadSessionView init(Long userId, InitUploadRequest request) {
        String safeName = fileService.validateUploadTarget(userId, request.folderId(),
                request.originalName(), request.sizeBytes());
        String fileHash = normalizeHash(request.fileHash());
        int chunkSize = resolveChunkSize(request.chunkSize());
        int totalChunks = (int) ((request.sizeBytes() + chunkSize - 1) / chunkSize);
        if (totalChunks > MAX_TOTAL_CHUNKS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "分片数量超过上限 " + MAX_TOTAL_CHUNKS + "，请调大 chunkSize");
        }
        String idempotencyKey = idempotencyKey(userId, request.folderId(), fileHash, request.sizeBytes());

        KnowledgeUploadSession existing = findActiveByKey(userId, idempotencyKey);
        if (existing != null) {
            return view(existing, true);
        }

        KnowledgeUploadSession session = new KnowledgeUploadSession();
        session.setUserId(userId);
        session.setUploadId(UUID.randomUUID().toString());
        session.setFolderId(request.folderId());
        session.setOriginalName(safeName);
        session.setContentType(request.contentType());
        session.setSizeBytes(request.sizeBytes());
        session.setChunkSize(chunkSize);
        session.setTotalChunks(totalChunks);
        session.setFileHash(fileHash);
        session.setReceivedChunks(0);
        session.setStatus(UploadSessionStatus.UPLOADING.name());
        session.setIdempotencyKey(idempotencyKey);
        session.setExpiresAt(LocalDateTime.now().plusHours(properties.getSessionTtlHours()));
        try {
            sessionMapper.insert(session);
        } catch (DuplicateKeyException e) {
            // 两个请求同时 init 同一个文件：唯一索引挡住后一个，把先建成的那个会话返回给它续传。
            KnowledgeUploadSession winner = findActiveByKey(userId, idempotencyKey);
            if (winner == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "该文件已有上传会话，请稍后重试");
            }
            return view(winner, true);
        }
        return view(session, false);
    }

    /** 查会话进度。断线重连后不想重新 init 的客户端可以直接用它拿 {@code missingChunks}。 */
    @Transactional(readOnly = true)
    public KnowledgeUploadSessionView status(Long userId, String uploadId) {
        return view(requireSession(userId, uploadId), false);
    }

    /**
     * 接收一个分片。同一分片重复上传是幂等的：文件覆盖写，记录不重复插。
     *
     * <p>落盘后要核对分片大小：除最后一片外都必须正好是 {@code chunkSize}。少了这一步，
     * 一次被截断的传输会安静地留下一个短分片，直到合并后哈希不符才暴露，而那时已经无法定位是哪一片。</p>
     *
     * <p>不加 {@code @Transactional}：写盘是这段里最慢的动作，不该占着事务；
     * 落盘成功而记录没写上的最坏结果是客户端被要求重传这一片，代价可接受。</p>
     */
    public KnowledgeUploadSessionView uploadChunk(Long userId, String uploadId, Integer chunkIndex,
                                                  MultipartFile chunk) {
        KnowledgeUploadSession session = requireUploadingSession(userId, uploadId);
        if (chunkIndex == null || chunkIndex < 0 || chunkIndex >= session.getTotalChunks()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "分片序号超出范围，应为 0 到 " + (session.getTotalChunks() - 1));
        }
        if (chunk == null || chunk.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分片内容为空");
        }
        long expected = expectedChunkSize(session, chunkIndex);
        if (chunk.getSize() != expected) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "第 " + chunkIndex + " 片大小应为 " + expected + " 字节，实际 " + chunk.getSize());
        }
        try (InputStream in = chunk.getInputStream()) {
            stagingStore.saveChunk(uploadId, chunkIndex, in);
        } catch (IOException | IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "分片写入失败：" + e.getMessage());
        }
        long written;
        try {
            written = stagingStore.chunkSize(uploadId, chunkIndex);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "分片校验失败：" + e.getMessage());
        }
        if (written != expected) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "第 " + chunkIndex + " 片落盘大小不符，请重传该分片");
        }
        recordChunk(session, chunkIndex, (int) written);
        return view(requireSession(userId, uploadId), false);
    }

    /**
     * 登记一片。已登记过就只刷新会话计数，不再插重复行（表上有 {@code (sessionId, chunkIndex)} 唯一索引兜底）。
     *
     * <p>不需要事务：{@code receivedChunks} 是从 {@code knowledge_upload_chunk} 现算出来的展示值，
     * 插入与刷新之间即使断开，下一次上传或 {@code status} 查询就会把它算对。</p>
     */
    private void recordChunk(KnowledgeUploadSession session, int chunkIndex, int sizeBytes) {
        long exists = chunkMapper.selectCount(new LambdaQueryWrapper<KnowledgeUploadChunk>()
                .eq(KnowledgeUploadChunk::getSessionId, session.getId())
                .eq(KnowledgeUploadChunk::getChunkIndex, chunkIndex));
        if (exists == 0) {
            KnowledgeUploadChunk record = new KnowledgeUploadChunk();
            record.setSessionId(session.getId());
            record.setChunkIndex(chunkIndex);
            record.setSizeBytes(sizeBytes);
            try {
                chunkMapper.insert(record);
            } catch (DuplicateKeyException e) {
                // 同一片被并发上传两次，后到的那次什么都不用做。
                log.debug("[知识库] 分片记录已存在，sessionId={} index={}", session.getId(), chunkIndex);
            }
        }
        sessionMapper.update(null, new LambdaUpdateWrapper<KnowledgeUploadSession>()
                .eq(KnowledgeUploadSession::getId, session.getId())
                .set(KnowledgeUploadSession::getReceivedChunks, countChunks(session.getId())));
    }

    /**
     * 合并分片并入库。
     *
     * <p>幂等：会话已完成时直接返回上次生成的那个文件，不会重复合并、也不会重复入库。
     * 并发保护靠一次条件更新把会话「抢」到手（{@code status} 只能从 UPLOADING 改成 COMPLETED 一次），
     * 抢不到的请求走幂等分支。抢到之后如果合并或入库失败，会把会话退回 UPLOADING 让客户端能重试。</p>
     *
     * <p>不加 {@code @Transactional}：这段里有合并大文件与对象存储上传两个慢动作。</p>
     *
     * @param tagNames 上传时一并提交的手动标签，可为 null
     */
    public KnowledgeFileView complete(Long userId, String uploadId, List<String> tagNames) {
        KnowledgeUploadSession session = requireSession(userId, uploadId);
        if (UploadSessionStatus.COMPLETED.name().equals(session.getStatus())) {
            return completedFileView(userId, session);
        }
        requireUploadingSession(userId, uploadId);

        List<Integer> missing = missingChunks(session);
        if (!missing.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "还有 " + missing.size() + " 个分片未上传，缺失序号：" + preview(missing));
        }

        int claimed = sessionMapper.update(null, new LambdaUpdateWrapper<KnowledgeUploadSession>()
                .eq(KnowledgeUploadSession::getId, session.getId())
                .eq(KnowledgeUploadSession::getStatus, UploadSessionStatus.UPLOADING.name())
                .set(KnowledgeUploadSession::getStatus, UploadSessionStatus.COMPLETED.name())
                // 终态会话释放去重键，同一个文件日后还能重新上传。
                .set(KnowledgeUploadSession::getIdempotencyKey, null));
        if (claimed != 1) {
            return completedFileView(userId, requireSession(userId, uploadId));
        }

        ChunkStagingStore.MergeResult merged;
        try {
            merged = stagingStore.merge(uploadId, session.getTotalChunks());
        } catch (IOException | IllegalArgumentException e) {
            restoreUploading(session);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "分片合并失败：" + e.getMessage());
        }
        if (merged.sizeBytes() != session.getSizeBytes()) {
            abortAfterCorruption(session);
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "合并后大小与声明不一致（" + merged.sizeBytes() + " / " + session.getSizeBytes()
                            + "），会话已作废，请重新上传");
        }
        if (!merged.sha256().equalsIgnoreCase(session.getFileHash())) {
            // 哈希不符说明某一片内容坏了，但已经无法判断是哪一片，只能整个会话作废重来。
            abortAfterCorruption(session);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "合并后文件校验不一致，会话已作废，请重新上传");
        }

        byte[] content;
        try {
            content = stagingStore.readMerged(uploadId);
        } catch (IOException e) {
            restoreUploading(session);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "读取合并结果失败：" + e.getMessage());
        }

        KnowledgeFile file;
        try {
            file = fileService.store(userId, session.getFolderId(), session.getOriginalName(),
                    session.getContentType(), content, merged.sha256(), session.getId(), tagNames);
        } catch (RuntimeException e) {
            restoreUploading(session);
            throw e;
        }
        sessionMapper.update(null, new LambdaUpdateWrapper<KnowledgeUploadSession>()
                .eq(KnowledgeUploadSession::getId, session.getId())
                .set(KnowledgeUploadSession::getFileId, file.getId())
                .set(KnowledgeUploadSession::getReceivedChunks, session.getTotalChunks()));
        stagingStore.discardQuietly(uploadId);
        return fileService.detail(userId, file.getId());
    }

    /** 放弃上传：作废会话并清掉暂存分片。已完成的会话不能再放弃。 */
    @Transactional
    public void abort(Long userId, String uploadId) {
        KnowledgeUploadSession session = requireSession(userId, uploadId);
        if (UploadSessionStatus.COMPLETED.name().equals(session.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该会话已完成上传，无法放弃");
        }
        terminate(session, UploadSessionStatus.ABORTED);
        stagingStore.discardQuietly(uploadId);
    }

    /** 抢占失败或已完成时按幂等语义返回既有文件；还没合并完就让客户端稍后再问。 */
    private KnowledgeFileView completedFileView(Long userId, KnowledgeUploadSession session) {
        if (session.getFileId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "上传正在合并中，请稍后重试");
        }
        KnowledgeFile file = fileMapper.selectOne(new LambdaQueryWrapper<KnowledgeFile>()
                .eq(KnowledgeFile::getId, session.getFileId())
                .eq(KnowledgeFile::getUserId, userId));
        if (file == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该会话生成的文件已被删除，请重新上传");
        }
        return fileService.detail(userId, file.getId());
    }

    /** 抢占之后失败：把会话退回可续传状态，连去重键一起还原，客户端可以重试完成。 */
    private void restoreUploading(KnowledgeUploadSession session) {
        sessionMapper.update(null, new LambdaUpdateWrapper<KnowledgeUploadSession>()
                .eq(KnowledgeUploadSession::getId, session.getId())
                .set(KnowledgeUploadSession::getStatus, UploadSessionStatus.UPLOADING.name())
                .set(KnowledgeUploadSession::getIdempotencyKey, session.getIdempotencyKey()));
    }

    /** 内容校验不过：会话直接作废并清掉暂存，避免把一个坏文件留在暂存区反复尝试。 */
    private void abortAfterCorruption(KnowledgeUploadSession session) {
        terminate(session, UploadSessionStatus.ABORTED);
        stagingStore.discardQuietly(session.getUploadId());
    }

    private void terminate(KnowledgeUploadSession session, UploadSessionStatus status) {
        sessionMapper.update(null, new LambdaUpdateWrapper<KnowledgeUploadSession>()
                .eq(KnowledgeUploadSession::getId, session.getId())
                .set(KnowledgeUploadSession::getStatus, status.name())
                .set(KnowledgeUploadSession::getIdempotencyKey, null));
    }

    private KnowledgeUploadSession requireSession(Long userId, String uploadId) {
        requireUser(userId);
        KnowledgeUploadSession session = uploadId == null || uploadId.isBlank() ? null
                : sessionMapper.selectOne(new LambdaQueryWrapper<KnowledgeUploadSession>()
                        .eq(KnowledgeUploadSession::getUploadId, uploadId)
                        .eq(KnowledgeUploadSession::getUserId, userId));
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "上传会话不存在");
        }
        return session;
    }

    private KnowledgeUploadSession requireUploadingSession(Long userId, String uploadId) {
        KnowledgeUploadSession session = requireSession(userId, uploadId);
        if (!UploadSessionStatus.UPLOADING.name().equals(session.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "上传会话状态为 " + session.getStatus() + "，不能继续上传");
        }
        if (session.getExpiresAt() != null && session.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "上传会话已过期，请重新初始化");
        }
        return session;
    }

    /** 命中活跃会话才算续传：过期的会话不能再接分片，让客户端重新初始化。 */
    private KnowledgeUploadSession findActiveByKey(Long userId, String idempotencyKey) {
        KnowledgeUploadSession session = sessionMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeUploadSession>()
                        .eq(KnowledgeUploadSession::getUserId, userId)
                        .eq(KnowledgeUploadSession::getIdempotencyKey, idempotencyKey)
                        .last("LIMIT 1"));
        if (session == null || !UploadSessionStatus.UPLOADING.name().equals(session.getStatus())) {
            return null;
        }
        if (session.getExpiresAt() != null && session.getExpiresAt().isBefore(LocalDateTime.now())) {
            terminate(session, UploadSessionStatus.EXPIRED);
            stagingStore.discardQuietly(session.getUploadId());
            return null;
        }
        return session;
    }

    private KnowledgeUploadSessionView view(KnowledgeUploadSession session, boolean resumed) {
        return KnowledgeUploadSessionView.of(session, missingChunks(session), resumed);
    }

    /** 缺失分片序号。以 {@code knowledge_upload_chunk} 为权威来源，不看会话上的计数快照。 */
    private List<Integer> missingChunks(KnowledgeUploadSession session) {
        if (!UploadSessionStatus.UPLOADING.name().equals(session.getStatus())) {
            return List.of();
        }
        Set<Integer> received = new HashSet<>(chunkMapper.selectList(
                        new LambdaQueryWrapper<KnowledgeUploadChunk>()
                                .select(KnowledgeUploadChunk::getId, KnowledgeUploadChunk::getChunkIndex)
                                .eq(KnowledgeUploadChunk::getSessionId, session.getId()))
                .stream().map(KnowledgeUploadChunk::getChunkIndex).toList());
        List<Integer> missing = new ArrayList<>();
        int total = session.getTotalChunks() == null ? 0 : session.getTotalChunks();
        for (int index = 0; index < total; index++) {
            if (!received.contains(index)) {
                missing.add(index);
            }
        }
        return missing;
    }

    private int countChunks(Long sessionId) {
        return Math.toIntExact(chunkMapper.selectCount(new LambdaQueryWrapper<KnowledgeUploadChunk>()
                .eq(KnowledgeUploadChunk::getSessionId, sessionId)));
    }

    /** 除最后一片外都必须正好是 {@code chunkSize}；最后一片是余下的字节数。 */
    private long expectedChunkSize(KnowledgeUploadSession session, int chunkIndex) {
        long chunkSize = session.getChunkSize();
        if (chunkIndex < session.getTotalChunks() - 1) {
            return chunkSize;
        }
        return session.getSizeBytes() - chunkSize * (session.getTotalChunks() - 1);
    }

    /** 客户端的建议值只是建议：夹到配置允许的区间内，最终值由响应告知客户端。 */
    private int resolveChunkSize(Integer requested) {
        int size = requested == null || requested <= 0 ? DEFAULT_CHUNK_SIZE : requested;
        size = Math.max(size, properties.getMinChunkSize());
        size = Math.min(size, properties.getMaxChunkSize());
        return size;
    }

    private String normalizeHash(String fileHash) {
        String hash = fileHash == null ? "" : fileHash.trim().toLowerCase();
        if (!hash.matches("^[0-9a-f]{64}$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件校验值应为 64 位 SHA-256 十六进制串");
        }
        return hash;
    }

    /**
     * 活跃会话去重键。把「谁、传到哪、什么内容、多大」四件事一起摘要：
     * 只要这四项相同就认定是同一次上传的续传，而不是一次新上传。
     */
    private String idempotencyKey(Long userId, Long folderId, String fileHash, Long sizeBytes) {
        String raw = userId + "|" + folderId + "|" + fileHash + "|" + sizeBytes;
        return ChunkStagingStore.sha256Hex(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 缺失分片可能很多，报错信息里只列前几个，其余给个总数。 */
    private String preview(List<Integer> missing) {
        int limit = Math.min(missing.size(), 10);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            builder.append(i == 0 ? "" : ",").append(missing.get(i));
        }
        if (missing.size() > limit) {
            builder.append(" … 共 ").append(missing.size()).append(" 片");
        }
        return builder.toString();
    }

    private void requireUser(Long userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }
    }
}
