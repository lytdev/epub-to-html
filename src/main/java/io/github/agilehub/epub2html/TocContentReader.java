package io.github.agilehub.epub2html;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.parser.Parser;
import static io.github.agilehub.epub2html.EpubPaths.*;

/** 遍历目录树并填充各节点 content，读取阶段不向 HTML 组装器追加内容。 同一文件只解析一次，按锚点物理位置划分正文，最终输出顺序由目录树决定。 */
final class TocContentReader {
  /** 本次读取是否清除正文标识属性，不跨请求共享可变配置。 */
  private final boolean removeClasses;

  /** 默认保留 class 和 id，以兼容原有章节读取行为。 */
  TocContentReader() {
    this(false);
  }

  /**
   * 创建本次转换的内容读取器。
   *
   * @param removeClasses 是否在片段处理完毕后同时删除 class 和 id
   */
  TocContentReader(boolean removeClasses) {
    this.removeClasses = removeClasses;
  }

  /**
   * 填充目录项自己的内容，子内容不嵌入父节点。
   *
   * @param zip EPUB 归档
   * @param itemList 目录树，content 被原地更新
   * @param handler 资源处理策略
   * @param urls 跨节点共享的资源缓存
   * @throws IOException 目标文件、锚点不存在或资源处理失败时抛出
   */
  void read(
      ZipFile zip, List<TocItem> itemList, EpubResourceHandler handler, Map<String, String> urls)
      throws IOException {
    Map<String, List<TocItem>> files = new LinkedHashMap<>();
    collect(itemList, files);
    for (var file : files.entrySet()) {
      fillFile(zip, file.getKey(), file.getValue(), handler, urls);
    }
  }

  /**
   * @param itemList 当前层目录
   * @param files 按目标文件分组的读取计划
   */
  private void collect(List<TocItem> itemList, Map<String, List<TocItem>> files) {
    for (TocItem item : itemList) {
      item.setContent("");
      // 无链接分组保持空 content；标题只存于 label，子节点仍独立读取。
      if (item.getTarget() != null && !item.getTarget().isBlank()) {
        files.computeIfAbsent(targetFile(item.getTarget()), ignored -> new ArrayList<>()).add(item);
      }
      collect(item.getChildren(), files);
    }
  }

  /**
   * 完整 DOM 上先内联样式，然后按同文件目录边界裁剪各片段。
   *
   * @param zip 归档
   * @param path 文件路径
   * @param itemList 该文件的目录项（目录先序顺序）
   * @param handler 资源策略
   * @param urls 资源缓存
   * @throws IOException 文件或锚点无效、读取或资源处理失败时抛出
   */
  private void fillFile(
      ZipFile zip,
      String path,
      List<TocItem> itemList,
      EpubResourceHandler handler,
      Map<String, String> urls)
      throws IOException {
    Document source =
        Jsoup.parse(
            EpubArchive.read(zip, EpubArchive.requiredEntry(zip, path)), path, Parser.xmlParser());
    StyleProcessor.inlineStyles(zip, source, path);
    Element body = source.body();
    ChapterRange range = new ChapterRange(body);
    List<Boundary> boundaries = new ArrayList<>();
    for (TocItem item : itemList) {
      String fragment = targetFragment(item.getTarget());
      Element anchor =
          fragment == null || fragment.isEmpty() ? body : source.getElementById(fragment);
      if (anchor == null || (anchor != body && !anchor.parents().contains(body))) {
        throw new IOException("Missing EPUB navigation anchor: " + item.getTarget());
      }
      boundaries.add(new Boundary(item, range.start(anchor)));
    }
    // 稳定排序：同一位置多个目录项时，正文仅分给最后一项，其余项 content 为空。
    boundaries.sort(Comparator.comparingInt(Boundary::position));
    for (int i = 0; i < boundaries.size(); i++) {
      Boundary boundary = boundaries.get(i);
      int end = i + 1 < boundaries.size() ? boundaries.get(i + 1).position() : range.end();
      Document part = Document.createShell("");
      for (Node child : body.childNodes()) {
        Node copy = range.copy(child, boundary.position(), end);
        if (copy != null) part.body().appendChild(copy);
      }
      MediaProcessor.rewriteMedia(zip, part, path, handler, urls);
      FigureProcessor.wrapCaptions(part);
      saveContent(part, boundary.item());
    }
  }

  /**
   * 在定位、样式及资源处理完成后清理属性，并保存本级 HTML 片段。
   *
   * @param part 已处理的章节文档
   * @param item 接收片段的目录节点；target 和 children 不变
   */
  private void saveContent(Document part, TocItem item) {
    // 不能提前删除 id/class：目录锚点定位和 CSS 选择器仍需使用这些属性。
    if (removeClasses) {
      for (Element element : part.body().getAllElements()) {
        element.removeAttr("class");
        element.removeAttr("id");
      }
    }
    item.setContent(part.body().html());
  }

  /**
   * @param item 目录节点 @param position 在原文中的边界位置
   */
  private record Boundary(TocItem item, int position) {}
}
