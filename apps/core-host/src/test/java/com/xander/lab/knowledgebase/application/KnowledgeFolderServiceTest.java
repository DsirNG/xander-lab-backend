package com.xander.lab.knowledgebase.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.xander.lab.knowledgebase.api.KnowledgeFolderView;
import com.xander.lab.knowledgebase.domain.KnowledgeFile;
import com.xander.lab.knowledgebase.domain.KnowledgeFolder;
import com.xander.lab.knowledgebase.infrastructure.KnowledgeBaseProperties;
import com.xander.lab.knowledgebase.infrastructure.KnowledgeFileMapper;
import com.xander.lab.knowledgebase.infrastructure.KnowledgeFolderMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 文件夹树的不变量：层级不超上限、同级不同名、不能移进自己的子树、物化路径始终自洽。
 * 路径一旦写坏，子树查询与环检测会一起失效，所以这些都值一个用例。
 */
class KnowledgeFolderServiceTest {

    private KnowledgeFolderMapper folderMapper;
    private KnowledgeFileMapper fileMapper;
    private KnowledgeFileService fileService;
    private KnowledgeBaseProperties properties;
    private KnowledgeFolderService service;
    private AtomicLong nextId;

    @BeforeAll
    static void initializeMybatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, KnowledgeFolder.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeFile.class);
    }

    @BeforeEach
    void setUp() {
        folderMapper = mock(KnowledgeFolderMapper.class);
        fileMapper = mock(KnowledgeFileMapper.class);
        fileService = mock(KnowledgeFileService.class);
        properties = new KnowledgeBaseProperties();
        service = new KnowledgeFolderService(folderMapper, fileMapper, fileService, properties);
        nextId = new AtomicLong(100);

        when(folderMapper.selectCount(any())).thenReturn(0L);
        when(fileMapper.selectCount(any())).thenReturn(0L);
        when(fileMapper.selectList(any())).thenReturn(List.of());
        when(folderMapper.insert(any(KnowledgeFolder.class))).thenAnswer(invocation -> {
            KnowledgeFolder folder = invocation.getArgument(0);
            folder.setId(nextId.getAndIncrement());
            return 1;
        });
    }

    @Test
    void creatingAtTheRootWritesDepthOneAndAPathEndingInItsOwnId() {
        KnowledgeFolderView view = service.create(7L, null, "  读书笔记  ");

        assertThat(view.name()).isEqualTo("读书笔记");
        assertThat(view.depth()).isEqualTo(1);
        assertThat(view.parentId()).isEqualTo(KnowledgeFolder.ROOT_PARENT_ID);
        assertThat(view.path()).isEqualTo("/100/");
        // path 要以自增主键结尾，只能先插入拿到 id 再回填，所以必须有一次 update。
        verify(folderMapper).updateById(any(KnowledgeFolder.class));
    }

    @Test
    void creatingUnderAParentExtendsTheParentPath() {
        when(folderMapper.selectOne(any())).thenReturn(folder(3L, 7L, 0L, "父", "/3/", 1));

        KnowledgeFolderView view = service.create(7L, 3L, "子");

        assertThat(view.depth()).isEqualTo(2);
        assertThat(view.path()).isEqualTo("/3/100/");
    }

    @Test
    void creatingBeyondTheDepthCeilingIsRejected() {
        properties.setMaxFolderDepth(2);
        when(folderMapper.selectOne(any())).thenReturn(folder(3L, 7L, 0L, "父", "/3/", 2));

        assertThatThrownBy(() -> service.create(7L, 3L, "子"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("最多 2 层");
        verify(folderMapper, times(0)).insert(any(KnowledgeFolder.class));
    }

    @Test
    void aDuplicateSiblingNameIsRejected() {
        when(folderMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.create(7L, null, "读书笔记"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("同名文件夹");
    }

    @Test
    void aNameWithASlashIsRejectedBecauseThePathSeparatorIsStructural() {
        assertThatThrownBy(() -> service.create(7L, null, "a/b"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("斜杠");
    }

    @Test
    void movingAFolderIntoItsOwnSubtreeIsRejected() {
        KnowledgeFolder parent = folder(3L, 7L, 0L, "父", "/3/", 1);
        KnowledgeFolder child = folder(4L, 7L, 3L, "子", "/3/4/", 2);
        when(folderMapper.selectOne(any())).thenReturn(parent, child);

        assertThatThrownBy(() -> service.move(7L, 3L, 4L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("子目录");
    }

    @Test
    void movingAFolderRewritesTheWholeSubtreePathAndDepth() {
        KnowledgeFolder moved = folder(4L, 7L, 3L, "子", "/3/4/", 2);
        KnowledgeFolder grandChild = folder(5L, 7L, 4L, "孙", "/3/4/5/", 3);
        KnowledgeFolder target = folder(9L, 7L, 0L, "新家", "/9/", 1);
        when(folderMapper.selectOne(any())).thenReturn(moved, target, moved);
        when(folderMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(moved, grandChild)));

        KnowledgeFolderView view = service.move(7L, 4L, 9L);

        assertThat(view.path()).isEqualTo("/9/4/");
        assertThat(view.depth()).isEqualTo(2);
        assertThat(grandChild.getPath()).isEqualTo("/9/4/5/");
        assertThat(grandChild.getDepth()).isEqualTo(3);
        assertThat(moved.getParentId()).isEqualTo(9L);
    }

    @Test
    void movingIsRejectedWhenTheDeepestDescendantWouldExceedTheCeiling() {
        properties.setMaxFolderDepth(3);
        KnowledgeFolder moved = folder(4L, 7L, 0L, "子", "/4/", 1);
        KnowledgeFolder grandChild = folder(5L, 7L, 4L, "孙", "/4/5/", 2);
        KnowledgeFolder target = folder(9L, 7L, 0L, "新家", "/9/", 3);
        when(folderMapper.selectOne(any())).thenReturn(moved, target);
        when(folderMapper.selectList(any())).thenReturn(List.of(moved, grandChild));

        assertThatThrownBy(() -> service.move(7L, 4L, 9L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("移动后层级");
    }

    @Test
    void aNonEmptyFolderIsNotDeletedWithoutTheRecursiveFlag() {
        KnowledgeFolder target = folder(3L, 7L, 0L, "父", "/3/", 1);
        KnowledgeFolder child = folder(4L, 7L, 3L, "子", "/3/4/", 2);
        when(folderMapper.selectOne(any())).thenReturn(target);
        when(folderMapper.selectList(any())).thenReturn(List.of(target, child));

        assertThatThrownBy(() -> service.delete(7L, 3L, false))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("文件夹不为空");
        verifyNoInteractions(fileService);
    }

    @Test
    void aRecursiveDeleteRemovesEveryFileThroughTheFileServiceSoObjectsAreCleanedToo() {
        KnowledgeFolder target = folder(3L, 7L, 0L, "父", "/3/", 1);
        KnowledgeFolder child = folder(4L, 7L, 3L, "子", "/3/4/", 2);
        when(folderMapper.selectOne(any())).thenReturn(target);
        when(folderMapper.selectList(any())).thenReturn(List.of(target, child));
        when(fileMapper.selectCount(any())).thenReturn(2L);
        when(fileMapper.selectList(any())).thenReturn(List.of(file(11L), file(12L)));

        int deleted = service.delete(7L, 3L, true);

        assertThat(deleted).isEqualTo(2);
        // 直接删表会留下对象存储里的孤儿文件，必须走文件服务。
        verify(fileService).delete(7L, 11L);
        verify(fileService).delete(7L, 12L);
        verify(folderMapper).deleteBatchIds(List.of(3L, 4L));
    }

    @Test
    void theRootIsNotAnUploadTarget() {
        assertThatThrownBy(() -> service.requireUploadableFolder(7L, 0L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("根目录不能直接上传文件");
        assertThatThrownBy(() -> service.requireUploadableFolder(7L, null))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void anUnknownFolderIsNotFoundRatherThanNullDereferenced() {
        when(folderMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.requireUploadableFolder(7L, 999L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("文件夹不存在");
    }

    @Test
    void anonymousCallersAreRejectedBeforeAnyQuery() {
        assertThatThrownBy(() -> service.list(null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("未登录");
        verifyNoInteractions(folderMapper);
    }

    private static KnowledgeFolder folder(Long id, Long userId, Long parentId, String name,
                                         String path, int depth) {
        KnowledgeFolder folder = new KnowledgeFolder();
        folder.setId(id);
        folder.setUserId(userId);
        folder.setParentId(parentId);
        folder.setName(name);
        folder.setPath(path);
        folder.setDepth(depth);
        return folder;
    }

    private static KnowledgeFile file(Long id) {
        KnowledgeFile file = new KnowledgeFile();
        file.setId(id);
        file.setUserId(7L);
        file.setFolderId(3L);
        return file;
    }
}
