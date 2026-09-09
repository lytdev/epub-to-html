package io.github.agilehub.epub2html;

import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 仅供测试检查各节点片段；不作为生产库的 HTML 输出能力发布。 */
final class TocTestSupport {
  private TocTestSupport() {}

  /** 汇总片段以便断言全树的标题、正文和资源出现次数。 */
  static String content(List<TocItem> roots) {
    StringBuilder result = new StringBuilder();
    for (TocItem item : roots) {
      result.append(item.getContent()).append('\n');
      result.append(content(item.getChildren()));
    }
    return result.toString();
  }

  /** 递归比较全部字段，而不只比较拼接后的正文。 */
  static void assertTreeEquals(List<TocItem> expected, List<TocItem> actual) {
    assertEquals(expected.size(), actual.size());
    for (int i = 0; i < expected.size(); i++) {
      TocItem left = expected.get(i);
      TocItem right = actual.get(i);
      assertEquals(left.getTarget(), right.getTarget());
      assertEquals(left.getLevel(), right.getLevel());
      assertEquals(left.getLabel(), right.getLabel());
      assertEquals(left.getContent(), right.getContent());
      assertTreeEquals(left.getChildren(), right.getChildren());
    }
  }
}
