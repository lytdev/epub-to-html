package cn.p4u.eth;

import java.io.IOException;
import java.util.List;
import org.jsoup.nodes.Document;

/**
 * 章节片段的有序处理管道：媒体替换 → 图注组合 → 可选属性清理。
 *
 * <p>每个步骤都会执行，异常则终止后续步骤。它不是“找到一个处理者就停止”的职责链。
 * 样式内联必须在完整 DOM 上完成，所以刻意放在片段管道之外。
 */
final class FragmentPipeline {
  /** 小型函数接口允许步骤使用方法引用，无需为每个操作创建继承层次。 */
  @FunctionalInterface
  private interface Step {
    void apply(Document document, String path, ResourceResolver resources) throws IOException;
  }

  private final List<Step> steps;

  FragmentPipeline(boolean removeClasses) {
    Step media = (doc, path, resources) -> MediaProcessor.rewriteMedia(doc, path, resources);
    Step figures = (doc, path, resources) -> FigureProcessor.wrapCaptions(doc);
    Step cleanup = (doc, path, resources) -> removeIdentifiers(doc);
    // 顺序集中在一处，新增片段处理规则时不必修改目录遍历或边界裁剪代码。
    steps = removeClasses ? List.of(media, figures, cleanup) : List.of(media, figures);
  }

  /** 全部步骤成功后才返回 HTML，由读取器写入对应目录节点。 */
  String process(Document document, String path, ResourceResolver resources) throws IOException {
    for (Step step : steps) step.apply(document, path, resources);
    return document.body().html();
  }

  private static void removeIdentifiers(Document document) {
    // 必须最后清理：锚点定位和 CSS 匹配都依赖 id/class。
    for (var element : document.body().getAllElements()) {
      element.removeAttr("class");
      element.removeAttr("id");
    }
  }
}
