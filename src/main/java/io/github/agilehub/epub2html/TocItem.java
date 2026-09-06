package io.github.agilehub.epub2html;

/**
 * 一条目录记录，由 {@link NavigationReader} 创建，供 {@link HeadingProcessor} 插入标题。
 *
 * <p>record 自动提供 target()、level() 和 label() 访问器，分别返回下面的三个组成部分。
 * 记录本身不保存子节点；列表顺序与 level 共同描述原导航树。
 *
 * @param target 目标章节归档路径，可带 # 后的元素 id，例如 OPS/a.xhtml#intro
 * @param level 原始导航层级，从 1 开始；标题处理时才限制到 HTML 的 1 至 6
 * @param label 目录显示文字，用作新增标题的文本
 */
record TocItem(String target, int level, String label) {}
