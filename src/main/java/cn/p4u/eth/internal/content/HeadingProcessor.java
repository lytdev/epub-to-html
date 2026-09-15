package cn.p4u.eth.internal.content;

import cn.p4u.eth.internal.archive.NavigationReader;
import cn.p4u.eth.model.TocItem;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Tag;
import static cn.p4u.eth.internal.archive.EpubPaths.targetFile;
import static cn.p4u.eth.internal.archive.EpubPaths.targetFragment;

/**
 * 标题处理器，根据导航目标在章节 DOM 中插入标题。
 *
 * <p>它接收 {@link NavigationReader} 的解析结果，不重新读取目录文件。
 * 当前转换流水线不调用此历史工具：目录标题仅保存在 TocItem.label 中。
 * 优先将目录确认的原有标题转为 h1～h6，否则插入标题；不重排正文。
 */
final class HeadingProcessor {
  /** 仅提供静态方法，禁止创建没有用途的工具类实例。 */
  private HeadingProcessor() {}

  /**
   * 在正文对应位置插入目录标题，原地修改章节 DOM。
   *
   * <p>仅供独立标题处理使用，当前转换流水线不调用。标题置于对应正文块之前；目标为 body 时放在其内部开头，
   * 避免章节合并时丢失。段落内的行内锚点提升到所在正文块。
   * 原有节点确认为目录标题时改为 h1～h6，保留其属性和行内内容；
   * 普通正文不会转换为标题，缺失锚点的目录项会被跳过。
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
    // 同一个正文起点可以对应多条目录记录，保存上次插入的标题以保持目录顺序。
    Map<Element, Element> insertedTails = new IdentityHashMap<>();
    for (TocItem item : chapterToc) {
      // 例如 chapter.xhtml#intro：intro 是元素 id；没有片段则表示整章开头。
      String fragment = targetFragment(item.target());
      Element anchor = fragment == null || fragment.isEmpty()
          ? chapter.body() : chapter.getElementById(fragment);
      // EPUB 目录可能有失效链接；找不到锚点时保留正文并跳过该标题。
      if (anchor == null) continue;
      // HTML 只有 h1～h6，超过六层仍用 h6，原始层级另存到 data 属性中。
      int level = Math.min(6, Math.max(1, item.level()));
      // text() 以文本赋值，避免目录文字被当成 HTML 标签解析。
      Element heading = chapter.createElement("h" + level).text(item.label());
      heading.attr("data-epub-toc-level", String.valueOf(item.level()));
      // 保留原锚点元素，新增标题使用派生 id；这里未做全书范围的 id 冲突消解。
      if (fragment != null) heading.attr("id", fragment + "-heading");
      Element start = contentStart(anchor, chapter.body());
      // 不把 head 等非正文节点作为标题位置。
      if (start == null) continue;
      Element previous = insertedTails.get(start);
      // 原 EPUB 常用 div/p 加样式表达标题。只处理正文起点处与目录文字一致的标题，
      // 避免把包含标题和正文的整个容器改为 h 标签，或重复插入同一标题文字。
      Element existing = previous == null ? existingHeading(start, item.label()) : null;
      if (existing != null) {
        existing.tagName("h" + level);
        existing.attr("data-epub-toc-level", String.valueOf(item.level()));
        insertedTails.put(start, existing);
        continue;
      }
      if (previous != null) previous.after(heading);
      else if (start == chapter.body()) start.prependChild(heading);
      else start.before(heading);
      insertedTails.put(start, heading);
    }
  }

  /**
   * 从正文起点沿首个子元素寻找与目录文字一致的标题节点。
   *
   * @param start 目录指向的正文起点或 body
   * @param label 目录标题文字
   * @return 可安全改标签的标题节点；普通正文或混合内容返回 null
   */
  private static Element existingHeading(Element start, String label) {
    if (label.isBlank()) return null;
    Element candidate = start;
    while (candidate != null) {
      String tag = candidate.normalName();
      boolean headingTag = tag.equals("div") || tag.equals("p") || tag.matches("h[1-6]");
      // 标题允许 span/em 等行内内容，不允许嵌入段落、媒体或其他正文块。
      if (headingTag && candidate.text().equals(label.trim()) && hasOnlyInlineContent(candidate)) {
        return candidate;
      }
      // 自身已有文字时不能跳过它去找后面的标题，否则会改变语义上的起点。
      if (!candidate.ownText().isBlank()) return null;
      candidate = candidate.firstElementChild();
    }
    return null;
  }

  /**
   * 检查标题内部是否只有文本与行内标记。
   *
   * @param element 候选标题
   * @return 不含正文块和媒体时为 true
   */
  private static boolean hasOnlyInlineContent(Element element) {
    for (Element child : element.getAllElements()) {
      if (child == element) continue;
      if (Tag.valueOf(child.normalName()).isBlock()
          || child.normalName().matches("img|svg|video|audio|object|embed|iframe|br")) {
        return false;
      }
    }
    return true;
  }

  /**
   * 定位适合在前面拼接标题的正文节点。
   *
   * <p>h 标签不应放入 p、span 等行内容器。对行内锚点向上找到正文块，
   * 对 body 则由调用方把标题插入内部，确保保存 body HTML 片段时保留标题。
   *
   * @param anchor 目录指向的元素
   * @param body 当前章节正文根节点
   * @return 对应正文块或 body；锚点不属于正文时返回 null
   */
  private static Element contentStart(Element anchor, Element body) {
    Element ancestor = anchor;
    while (ancestor != null && ancestor != body) ancestor = ancestor.parent();
    if (ancestor == null) return null;
    Element start = anchor;
    // 章节按 XML 模式解析，节点自身的 Tag 不一定带 HTML 块级语义，需按标签名查询。
    while (start != body && start.parent() != body && !Tag.valueOf(start.normalName()).isBlock()) {
      start = start.parent();
    }
    return start;
  }
}
