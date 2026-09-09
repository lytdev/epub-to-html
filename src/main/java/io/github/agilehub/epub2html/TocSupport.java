package io.github.agilehub.epub2html;

import java.util.List;

/** 节点支撑类。 */
final class TocSupport {
  private TocSupport() {}

  /** 汇总片段以便断言全树的标题、正文和资源出现次数。 */
  static String contentToHtml(List<TocItem> itemList) {
    StringBuilder result = new StringBuilder();
    for (TocItem item : itemList) {
      result.append(item.getContent()).append('\n');
      result.append(contentToHtml(item.getChildren()));
    }
    return result.toString();
  }
}
