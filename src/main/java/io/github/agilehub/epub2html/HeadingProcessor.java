package io.github.agilehub.epub2html;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import static io.github.agilehub.epub2html.EpubPaths.targetFile;
import static io.github.agilehub.epub2html.EpubPaths.targetFragment;

/**
 * 标题处理器，根据导航目标在章节 DOM 中插入标题。
 *
 * <p>它接收 {@link NavigationReader} 的解析结果，不重新读取目录文件。
 * {@link ConversionPipeline} 在样式处理之后调用它，然后继续媒体处理与章节合并。
 * 本类只插入节点，不按目录重新排列正文，也不删除原有标题。
 */
final class HeadingProcessor {
  /** 仅提供静态方法，禁止创建没有用途的工具类实例。 */
  private HeadingProcessor() {}

  /**
   * 在正文对应位置插入目录标题，原地修改章节 DOM。
   *
   * <p>由流水线在样式处理之后调用。标题采用导航标签文本；原有标题或正文不会自动删除，缺失锚点的目录项会被跳过。修改后的章节再交给媒体处理器。
   *
   * @param chapter 当前章节 Jsoup 文档
   * @param chapterPath 当前章节归档路径，用于筛选属于本章的目录项
   * @param toc 由流水线建立的目标路径到目录项列表映射
   */
  static void injectTocHeadings(
      Document chapter, String chapterPath, Map<String, List<TocItem>> toc) {
    // 先筛出目标文件等于当前章节的条目，# 后的片段暂不参与文件比较。
    List<TocItem> chapterToc = new ArrayList<>();
    for (List<TocItem> items : toc.values())
      for (TocItem item : items)
        if (targetFile(item.target()).equals(chapterPath)) chapterToc.add(item);
    if (chapterToc.isEmpty()) return;
    for (TocItem item : chapterToc) {
      // 例如 chapter.xhtml#intro：intro 是元素 id；没有片段则表示整章开头。
      String fragment = targetFragment(item.target());
      Element anchor = fragment == null ? chapter.body() : chapter.getElementById(fragment);
      // EPUB 目录可能有失效链接；找不到锚点时保留正文并跳过该标题。
      if (anchor == null) continue;
      // HTML 只有 h1～h6，超过六层仍用 h6，原始层级另存到 data 属性中。
      int level = Math.min(6, Math.max(1, item.level()));
      // text() 以文本赋值，避免目录文字被当成 HTML 标签解析。
      Element heading = chapter.createElement("h" + level).text(item.label());
      heading.attr("data-epub-toc-level", String.valueOf(item.level()));
      // 保留原锚点元素，新增标题使用派生 id；这里未做全书范围的 id 冲突消解。
      if (fragment != null) heading.attr("id", fragment + "-heading");
      // 未带片段的目录项指向整章；章节 body 的子节点才会被合并，标题必须插入其内部。
      if (fragment == null) anchor.prependChild(heading);
      else anchor.before(heading);
    }
  }

}
