/**
 * 知识库应用层：文件夹树、文件、标签、章节与知识结构图的业务编排。
 *
 * <p>本层承接 {@code api} 层的入参、调用 {@code domain} 的纯计算与 {@code infrastructure}
 * 的 mapper，向上只返回 {@code api} 层的视图记录，不把实体暴露出去。</p>
 *
 * <p>依赖方向是单向的：{@code KnowledgeFolderService → KnowledgeFileService}（递归删除要用），
 * 因此 {@code KnowledgeFileService} 校验目标文件夹时直接查 mapper 而不是反向注入，
 * 否则就是循环依赖。</p>
 */
package com.xander.lab.knowledgebase.application;
