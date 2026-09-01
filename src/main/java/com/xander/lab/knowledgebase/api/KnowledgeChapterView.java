package com.xander.lab.knowledgebase.api;

import com.xander.lab.knowledgebase.domain.KnowledgeChapter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 章节目录的一个节点，供「知识结构预览」左侧目录使用。子节点内嵌，深度由标题层级决定。
 *
 * @param structureStatus 该章的结构图状态：大部头文件里没点开过的章是 PENDING
 */
public record KnowledgeChapterView(
        Long id,
        Long parentId,
        Integer level,
        Integer orderIndex,
        String title,
        Integer startOffset,
        Integer endOffset,
        Integer charCount,
        String structureStatus,
        List<KnowledgeChapterView> children) {

    /**
     * 把按 {@code orderIndex} 排好的扁平章节列表还原成树。
     *
     * <p>不看 {@code parentId} 而是按层级栈还原：只要顺序对，层级栈得到的结果与 parentId 一致，
     * 而且对历史数据里 parentId 缺失或错乱的情况更耐受。</p>
     */
    public static List<KnowledgeChapterView> tree(List<KnowledgeChapter> ordered) {
        List<Node> roots = new ArrayList<>();
        Deque<Node> ancestors = new ArrayDeque<>();
        for (KnowledgeChapter chapter : ordered) {
            int level = chapter.getLevel() == null ? 1 : chapter.getLevel();
            while (!ancestors.isEmpty() && ancestors.peek().level >= level) {
                ancestors.pop();
            }
            Node node = new Node(chapter, level);
            if (ancestors.isEmpty()) {
                roots.add(node);
            } else {
                ancestors.peek().children.add(node);
            }
            ancestors.push(node);
        }
        return roots.stream().map(Node::toView).toList();
    }

    /** 装树用的可变中间体：record 的 children 一旦构造就改不了。 */
    private static final class Node {
        private final KnowledgeChapter chapter;
        private final int level;
        private final List<Node> children = new ArrayList<>();

        private Node(KnowledgeChapter chapter, int level) {
            this.chapter = chapter;
            this.level = level;
        }

        private KnowledgeChapterView toView() {
            return new KnowledgeChapterView(chapter.getId(), chapter.getParentId(), chapter.getLevel(),
                    chapter.getOrderIndex(), chapter.getTitle(), chapter.getStartOffset(),
                    chapter.getEndOffset(), chapter.getCharCount(), chapter.getStructureStatus(),
                    children.stream().map(Node::toView).toList());
        }
    }
}
