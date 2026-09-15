package cn.p4u.eth.internal.content;

import cn.p4u.eth.callback.CallBackRecord;
import cn.p4u.eth.callback.FileParseCallback;
import cn.p4u.eth.internal.archive.EpubArchive;
import cn.p4u.eth.model.TocItem;
import cn.p4u.eth.resource.EpubResourceHandler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.parser.Parser;
import static cn.p4u.eth.internal.archive.EpubPaths.targetFragment;

/** 协调读取计划、完整 DOM 样式处理、边界裁剪和片段管道，将结果写回原目录节点。 */
public final class TocContentReader {
  /** 不可变的步骤配置；每次 read 的资源缓存通过独立会话传入。 */
  private final FragmentPipeline fragments;

  /** 默认保留 class 和 id，以兼容原有章节读取行为。 */
  public TocContentReader() {
    this(false);
  }

  /**
   * 创建本次转换的内容读取器。
   *
   * @param removeClasses 是否在片段处理完毕后同时删除 class 和 id
   */
  public TocContentReader(boolean removeClasses) {
    this.fragments = new FragmentPipeline(removeClasses);
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
  public void read(
      ZipFile zip, List<TocItem> itemList, EpubResourceHandler handler, Map<String, String> urls)
      throws IOException {
    read(zip, itemList, handler, urls, null);
  }

  /**
   * 填充目录项自己的内容，并逐「顶层内容项」触发进度回调。
   *
   * <p>每完成一个带 target 的顶层项调用一次 {@link FileParseCallback#onLineParsed}；
   * 内容处理异常时先调用 {@link FileParseCallback#onError}（序号为当前项）再向上抛出。
   * 返回顶层内容项总数，供调用方在 {@link FileParseCallback#onComplete} 中复用。
   *
   * @param zip EPUB 归档
   * @param itemList 目录树，content 被原地更新
   * @param handler 资源处理策略
   * @param urls 跨节点共享的资源缓存
   * @param callback 进度回调，可为 null
   * @return 带 target 的顶层内容项总数
   * @throws IOException 目标文件、锚点不存在或资源处理失败时抛出
   */
  public int read(
      ZipFile zip,
      List<TocItem> itemList,
      EpubResourceHandler handler,
      Map<String, String> urls,
      FileParseCallback<TocItem> callback)
      throws IOException {
    Map<String, List<TocItem>> files = ChapterReadPlan.collect(itemList);
    ResourceResolver resources = new ResourceResolver(zip, handler, urls);
    // 只有带 target 的顶层项才是真正会被解析的内容项；无链接分组节点不参与计数与通知。
    int total =
        (int)
            itemList.stream()
                .filter(i -> i.getTarget() != null && !i.getTarget().isBlank())
                .count();
    int count = 0;
    for (var file : files.entrySet()) {
      try {
        fillFile(zip, file.getKey(), file.getValue(), resources);
      } catch (IOException | RuntimeException ex) {
        // 当前项序号（尽力）：尚未完成的下一个顶层项。
        if (callback != null) callback.onError(ex, count + 1);
        throw ex;
      }
      for (TocItem item : file.getValue()) {
        // 依据对象引用判断顶层项：同一文件内的子项不触发通知。
        if (itemList.contains(item)) {
          count++;
          if (callback != null) {
            callback.onLineParsed(
                count,
                total,
                new CallBackRecord<>(item, item.getTarget(), item.getLabel(), item.getContent()));
          }
        }
      }
    }
    return total;
  }

  /**
   * 完整 DOM 上先内联样式，然后按同文件目录边界裁剪各片段。
   *
   * @param zip 归档
   * @param path 文件路径
   * @param itemList 该文件的目录项（目录先序顺序）
   * @param resources 本次转换共享的资源会话
   * @throws IOException 文件或锚点无效、读取或资源处理失败时抛出
   */
  private void fillFile(
      ZipFile zip,
      String path,
      List<TocItem> itemList,
      ResourceResolver resources)
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
      boundary.item().setContent(fragments.process(part, path, resources));
    }
  }

  /**
   * @param item 目录节点
   * @param position 在原文中的边界位置
   */
  private record Boundary(TocItem item, int position) {}
}
