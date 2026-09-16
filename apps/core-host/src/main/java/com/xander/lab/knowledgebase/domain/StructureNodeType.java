package com.xander.lab.knowledgebase.domain;

/** 知识结构图节点的类型。 */
public enum StructureNodeType {
    /** 本次生成的根节点：整本书维度是文件，按章维度是那一章。 */
    ROOT,
    /** 章（顶层标题）。 */
    CHAPTER,
    /** 节（章下面的标题）。 */
    SECTION,
    /** 知识点：从正文里抽出的要点行，没有对应的章节记录。 */
    POINT
}
