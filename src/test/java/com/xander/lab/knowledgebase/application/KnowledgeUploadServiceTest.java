package com.xander.lab.knowledgebase.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分片上传的三件要紧事：断线能续、同一片重复上传无害、内容坏了要被拦住。
 * 这些行为都在服务层，不需要真的落盘或连库就能验证。
 */
class KnowledgeUploadServiceTest {

    private static final String HASH = "a".repeat(64);
    private static final String UPLOAD_ID = "11111111-2222-3333-4444-555555555555";

    private KnowledgeUploadSessionMapper sessionMapper;
    private KnowledgeUploadChunkMapper chunkMapper;
    private KnowledgeFileMapper fileMapper;
    private KnowledgeFileService fileService;
    private ChunkStagingStore stagingStore;
    private KnowledgeBaseProperties properties;
    private KnowledgeUploadService service;

    @BeforeAll
    static void initializeMybatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, KnowledgeUploadSession.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeUploadChunk.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeFile.class);
    }

    @BeforeEach
    void setUp() {
        sessionMapper = mock(KnowledgeUploadSessionMapper.class);
        chunkMapper = mock(KnowledgeUploadChunkMapper.class);
        fileMapper = mock(KnowledgeFileMapper.class);
        fileService = mock(KnowledgeFileService.class);
        stagingStore = mock(ChunkStagingStore.class);
        properties = new KnowledgeBaseProperties();
        service = new KnowledgeUploadService(sessionMapper, chunkMapper, fileMapper, fileService,
                stagingStore, properties);

        when(fileService.validateUploadTarget(any(), any(), anyString(), anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        when(chunkMapper.selectList(any())).thenReturn(List.of());
        when(chunkMapper.selectCount(any())).thenReturn(0L);
        when(sessionMapper.update(isNull(), any())).thenReturn(1);
        when(sessionMapper.insert(any(KnowledgeUploadSession.class))).thenAnswer(invocation -> {
            KnowledgeUploadSession session = invocation.getArgument(0);
            session.setId(42L);
            return 1;
        });
    }

    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }

    @Test
    void initComputesTheChunkPlanItselfAndReportsEveryChunkAsMissing() {
        InitUploadRequest request = new InitUploadRequest(3L, "book.md", "text/markdown",
                12_000_000L, null, HASH);

        KnowledgeUploadSessionView view = service.init(7L, request);

        // 客户端没给 chunkSize，用默认 5MB；片数由服务端算，不采信客户端传值。
        assertThat(view.chunkSize()).isEqualTo(5 * 1024 * 1024);
        assertThat(view.totalChunks()).isEqualTo(3);
        assertThat(view.missingChunks()).containsExactly(0, 1, 2);
        assertThat(view.resumed()).isFalse();
        assertThat(view.status()).isEqualTo(UploadSessionStatus.UPLOADING.name());
        assertThat(view.uploadId()).isNotBlank();
    }

    @Test
    void aSuggestedChunkSizeIsClampedIntoTheAllowedRange() {
        KnowledgeUploadSessionView tooSmall = service.init(7L,
                new InitUploadRequest(3L, "book.md", null, 1_000_000L, 1000, HASH));

        assertThat(tooSmall.chunkSize()).isEqualTo(properties.getMinChunkSize());
        assertThat(tooSmall.totalChunks()).isEqualTo(4);
    }

    @Test
    void aChunkPlanWithTooManyChunksIsRejectedRatherThanAccepted() {
        long size = (long) properties.getMinChunkSize() * 10_001;

        assertThatThrownBy(() -> service.init(7L,
                new InitUploadRequest(3L, "book.md", null, size, 1, HASH)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("分片数量超过上限");
        verify(sessionMapper, never()).insert(any(KnowledgeUploadSession.class));
    }

    @Test
    void aFileHashThatIsNotSha256HexIsRejected() {
        assertThatThrownBy(() -> service.init(7L,
                new InitUploadRequest(3L, "book.md", null, 100L, null, "not-a-hash")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("64 位 SHA-256");
    }

    @Test
    void reInitAfterADroppedConnectionResumesTheSameSessionAndListsOnlyWhatIsMissing() {
        when(sessionMapper.selectOne(any())).thenReturn(session(UploadSessionStatus.UPLOADING.name(), 1));
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk(0)));

        KnowledgeUploadSessionView view = service.init(7L,
                new InitUploadRequest(3L, "book.md", "text/markdown", 10L, 4, HASH));

        // 断线续传的全部要求就是这两条：认出旧会话，并告诉客户端还缺哪几片。
        assertThat(view.resumed()).isTrue();
        assertThat(view.uploadId()).isEqualTo(UPLOAD_ID);
        assertThat(view.missingChunks()).containsExactly(1, 2);
        verify(sessionMapper, never()).insert(any(KnowledgeUploadSession.class));
    }

    @Test
    void anExpiredSessionIsRetiredInsteadOfResumed() {
        KnowledgeUploadSession expired = session(UploadSessionStatus.UPLOADING.name(), 1);
        expired.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(sessionMapper.selectOne(any())).thenReturn(expired);

        KnowledgeUploadSessionView view = service.init(7L,
                new InitUploadRequest(3L, "book.md", null, 10L, 4, HASH));

        assertThat(view.resumed()).isFalse();
        assertThat(view.uploadId()).isNotEqualTo(UPLOAD_ID);
        assertThat(capturedUpdateValues()).contains(UploadSessionStatus.EXPIRED.name());
        // 过期会话的暂存分片没人会再用，留着就是垃圾。
        verify(stagingStore).discardQuietly(UPLOAD_ID);
        verify(sessionMapper).insert(any(KnowledgeUploadSession.class));
    }

    @Test
    void twoConcurrentInitsEndUpOnTheSameSessionInsteadOfFailing() {
        KnowledgeUploadSession winner = session(UploadSessionStatus.UPLOADING.name(), 0);
        // 第一次查没有活跃会话，插入撞唯一索引，再查就能看到先建成的那个。
        when(sessionMapper.selectOne(any())).thenReturn(null, winner);
        when(sessionMapper.insert(any(KnowledgeUploadSession.class)))
                .thenThrow(new DuplicateKeyException("uk_active_upload"));

        KnowledgeUploadSessionView view = service.init(7L,
                new InitUploadRequest(3L, "book.md", null, 10L, 4, HASH));

        assertThat(view.resumed()).isTrue();
        assertThat(view.uploadId()).isEqualTo(UPLOAD_ID);
    }

    @Test
    void aChunkIsRecordedOnceItIsOnDiskWithTheExpectedSize() throws Exception {
        when(sessionMapper.selectOne(any())).thenReturn(session(UploadSessionStatus.UPLOADING.name(), 0));
        when(stagingStore.chunkSize(eq(UPLOAD_ID), eq(2))).thenReturn(2L);
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk(2)));

        KnowledgeUploadSessionView view = service.uploadChunk(7L, UPLOAD_ID, 2, part("bb"));

        // 最后一片是余数：10 - 4*2 = 2 字节，不该按 chunkSize 要求它。
        verify(stagingStore).saveChunk(eq(UPLOAD_ID), eq(2), any());
        verify(chunkMapper).insert(any(KnowledgeUploadChunk.class));
        assertThat(view.missingChunks()).containsExactly(0, 1);
    }

    @Test
    void aShortChunkIsRejectedBeforeItEverTouchesTheStagingArea() throws Exception {
        when(sessionMapper.selectOne(any())).thenReturn(session(UploadSessionStatus.UPLOADING.name(), 0));

        assertThatThrownBy(() -> service.uploadChunk(7L, UPLOAD_ID, 0, part("ab")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("第 0 片大小应为 4 字节，实际 2");
        verify(stagingStore, never()).saveChunk(anyString(), anyInt(), any());
    }

    @Test
    void aChunkIndexOutsideThePlanIsRejected() {
        when(sessionMapper.selectOne(any())).thenReturn(session(UploadSessionStatus.UPLOADING.name(), 0));

        assertThatThrownBy(() -> service.uploadChunk(7L, UPLOAD_ID, 3, part("aaaa")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("分片序号超出范围，应为 0 到 2");
    }

    @Test
    void aChunkThatLandedOnDiskAtTheWrongSizeIsNotRecorded() throws Exception {
        when(sessionMapper.selectOne(any())).thenReturn(session(UploadSessionStatus.UPLOADING.name(), 0));
        when(stagingStore.chunkSize(eq(UPLOAD_ID), eq(0))).thenReturn(1L);

        // 传输被截断时请求头里的长度仍是对的，只有落盘大小能揭发它。
        assertThatThrownBy(() -> service.uploadChunk(7L, UPLOAD_ID, 0, part("aaaa")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("落盘大小不符");
        verify(chunkMapper, never()).insert(any(KnowledgeUploadChunk.class));
    }

    @Test
    void reUploadingTheSameChunkIsHarmless() throws Exception {
        when(sessionMapper.selectOne(any())).thenReturn(session(UploadSessionStatus.UPLOADING.name(), 1));
        when(stagingStore.chunkSize(eq(UPLOAD_ID), eq(0))).thenReturn(4L);
        when(chunkMapper.selectCount(any())).thenReturn(1L);
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk(0)));

        service.uploadChunk(7L, UPLOAD_ID, 0, part("aaaa"));

        // 分片文件覆盖写，登记行不重复插——客户端重试同一片不会把计数搞乱。
        verify(stagingStore).saveChunk(eq(UPLOAD_ID), eq(0), any());
        verify(chunkMapper, never()).insert(any(KnowledgeUploadChunk.class));
    }

    @Test
    void chunksAreRefusedOnceTheSessionIsNoLongerUploading() {
        when(sessionMapper.selectOne(any())).thenReturn(session(UploadSessionStatus.ABORTED.name(), 1));

        assertThatThrownBy(() -> service.uploadChunk(7L, UPLOAD_ID, 0, part("aaaa")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("上传会话状态为 ABORTED");
    }

    @Test
    void chunksAreRefusedAfterTheSessionExpires() {
        KnowledgeUploadSession expired = session(UploadSessionStatus.UPLOADING.name(), 1);
        expired.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(sessionMapper.selectOne(any())).thenReturn(expired);

        assertThatThrownBy(() -> service.uploadChunk(7L, UPLOAD_ID, 0, part("aaaa")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("上传会话已过期");
    }

    @Test
    void anUnknownUploadIdIsNotFound() {
        when(sessionMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.status(7L, UPLOAD_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("上传会话不存在");
    }

    @Test
    void anonymousCallersAreRejectedBeforeAnyQuery() {
        assertThatThrownBy(() -> service.status(null, UPLOAD_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("未登录");
    }

    @Test
    void completingWithChunksStillMissingIsRefusedAndSaysWhichOnes() {
        when(sessionMapper.selectOne(any())).thenReturn(session(UploadSessionStatus.UPLOADING.name(), 1));
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk(1)));

        assertThatThrownBy(() -> service.complete(7L, UPLOAD_ID, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("还有 2 个分片未上传，缺失序号：0,2");
        verify(fileService, never()).store(any(), any(), anyString(), any(), any(), anyString(), any(), any());
    }

    @Test
    void completingMergesTheChunksAndHandsTheBytesToTheFileService() throws Exception {
        KnowledgeUploadSession session = session(UploadSessionStatus.UPLOADING.name(), 3);
        when(sessionMapper.selectOne(any())).thenReturn(session);
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk(0), chunk(1), chunk(2)));
        when(stagingStore.merge(UPLOAD_ID, 3))
                .thenReturn(new ChunkStagingStore.MergeResult(Path.of("merged.bin"), 10L, HASH));
        when(stagingStore.readMerged(UPLOAD_ID)).thenReturn("0123456789".getBytes());
        when(fileService.store(eq(7L), eq(3L), eq("book.md"), eq("text/markdown"), any(),
                eq(HASH), eq(42L), any())).thenReturn(storedFile());
        when(fileService.detail(7L, 500L)).thenReturn(KnowledgeFileView.of(storedFile(), List.of()));

        KnowledgeFileView view = service.complete(7L, UPLOAD_ID, List.of("读书"));

        assertThat(view.id()).isEqualTo(500L);
        // 会话抢占成功才会合并，抢占本身就是那条 UPLOADING → COMPLETED 的条件更新。
        assertThat(capturedUpdateValues()).contains(UploadSessionStatus.COMPLETED.name());
        verify(stagingStore).discardQuietly(UPLOAD_ID);
    }

    @Test
    void aHashMismatchAfterMergeVoidsTheSessionInsteadOfStoringTheFile() throws Exception {
        when(sessionMapper.selectOne(any())).thenReturn(session(UploadSessionStatus.UPLOADING.name(), 3));
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk(0), chunk(1), chunk(2)));
        when(stagingStore.merge(UPLOAD_ID, 3)).thenReturn(
                new ChunkStagingStore.MergeResult(Path.of("merged.bin"), 10L, "b".repeat(64)));

        assertThatThrownBy(() -> service.complete(7L, UPLOAD_ID, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("合并后文件校验不一致");
        // 坏内容既不能入库，也不该留在暂存区被反复重试。
        verify(fileService, never()).store(any(), any(), anyString(), any(), any(), anyString(), any(), any());
        assertThat(capturedUpdateValues()).contains(UploadSessionStatus.ABORTED.name());
        verify(stagingStore).discardQuietly(UPLOAD_ID);
    }

    @Test
    void aSizeMismatchAfterMergeVoidsTheSessionToo() throws Exception {
        when(sessionMapper.selectOne(any())).thenReturn(session(UploadSessionStatus.UPLOADING.name(), 3));
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk(0), chunk(1), chunk(2)));
        when(stagingStore.merge(UPLOAD_ID, 3))
                .thenReturn(new ChunkStagingStore.MergeResult(Path.of("merged.bin"), 9L, HASH));

        assertThatThrownBy(() -> service.complete(7L, UPLOAD_ID, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("合并后大小与声明不一致");
        assertThat(capturedUpdateValues()).contains(UploadSessionStatus.ABORTED.name());
    }

    @Test
    void aFailedStoreReturnsTheSessionToUploadingSoTheClientCanRetry() throws Exception {
        when(sessionMapper.selectOne(any())).thenReturn(session(UploadSessionStatus.UPLOADING.name(), 3));
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk(0), chunk(1), chunk(2)));
        when(stagingStore.merge(UPLOAD_ID, 3))
                .thenReturn(new ChunkStagingStore.MergeResult(Path.of("merged.bin"), 10L, HASH));
        when(stagingStore.readMerged(UPLOAD_ID)).thenReturn("0123456789".getBytes());
        when(fileService.store(any(), any(), anyString(), any(), any(), anyString(), any(), any()))
                .thenThrow(new IllegalStateException("对象存储不可用"));

        assertThatThrownBy(() -> service.complete(7L, UPLOAD_ID, null))
                .isInstanceOf(IllegalStateException.class);
        // 抢占后失败必须退回 UPLOADING 并还原去重键，否则会话卡在 COMPLETED 却没有文件。
        assertThat(lastUpdateValues())
                .contains(UploadSessionStatus.UPLOADING.name())
                .contains("key");
        verify(stagingStore, never()).discardQuietly(anyString());
    }

    @Test
    void aFailedMergeAlsoReturnsTheSessionToUploading() throws Exception {
        when(sessionMapper.selectOne(any())).thenReturn(session(UploadSessionStatus.UPLOADING.name(), 3));
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk(0), chunk(1), chunk(2)));
        when(stagingStore.merge(UPLOAD_ID, 3)).thenThrow(new java.io.IOException("分片缺失：1"));

        assertThatThrownBy(() -> service.complete(7L, UPLOAD_ID, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("分片合并失败");
        assertThat(lastUpdateValues()).contains(UploadSessionStatus.UPLOADING.name(), "key");
    }

    // PLACEHOLDER_TESTS

    private KnowledgeUploadSession session(String status, Integer receivedChunks) {
        KnowledgeUploadSession session = new KnowledgeUploadSession();
        session.setId(42L);
        session.setUserId(7L);
        session.setUploadId(UPLOAD_ID);
        session.setFolderId(3L);
        session.setOriginalName("book.md");
        session.setContentType("text/markdown");
        session.setSizeBytes(10L);
        session.setChunkSize(4);
        session.setTotalChunks(3);
        session.setFileHash(HASH);
        session.setReceivedChunks(receivedChunks);
        session.setStatus(status);
        session.setIdempotencyKey("key");
        session.setExpiresAt(LocalDateTime.now().plusHours(1));
        return session;
    }

    private static KnowledgeUploadChunk chunk(int index) {
        KnowledgeUploadChunk chunk = new KnowledgeUploadChunk();
        chunk.setSessionId(42L);
        chunk.setChunkIndex(index);
        chunk.setSizeBytes(4);
        return chunk;
    }

    private static KnowledgeFile storedFile() {
        KnowledgeFile file = new KnowledgeFile();
        file.setId(500L);
        file.setUserId(7L);
        file.setFolderId(3L);
        file.setDisplayName("book.md");
        return file;
    }

    /** set(...) 的值走参数占位符，断言要看 paramNameValuePairs 而不是 SQL 片段。 */
    private List<Object> capturedUpdateValues() {
        ArgumentCaptor<LambdaUpdateWrapper<KnowledgeUploadSession>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(sessionMapper, org.mockito.Mockito.atLeastOnce()).update(isNull(), captor.capture());
        List<Object> values = new ArrayList<>();
        captor.getAllValues().forEach(wrapper -> values.addAll(wrapper.getParamNameValuePairs().values()));
        return values;
    }
}
