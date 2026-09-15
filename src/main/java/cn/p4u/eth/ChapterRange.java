package cn.p4u.eth;

import java.util.IdentityHashMap;
import java.util.Map;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;

/**
 * 将章节 DOM 按先序节点区间裁剪，保留区间内正文及必要祖先结构。
 * 用于同一个 XHTML 被多个目录锚点引用时避免整页重复。
 */
final class ChapterRange {
  private final Map<Node, Integer> starts = new IdentityHashMap<>();
  private final Map<Node, Integer> ends = new IdentityHashMap<>();
  private int next;

  /** @param body 要建立位置索引的正文根节点 */
  ChapterRange(Element body) {
    index(body);
  }

  /** @param node 节点 @return 节点的先序位置 */
  int start(Node node) { return starts.get(node); }

  /** @return 正文结束位置（不包含） */
  int end() { return next; }

  /** @param node 当前节点，递归记录其子树结束位置 */
  private void index(Node node) {
    starts.put(node, next++);
    for (Node child : node.childNodes()) index(child);
    ends.put(node, next);
  }

  /**
   * 克隆指定区间，跨边界的祖先仅保留属于区间的子节点。
   *
   * @param node 要检查的节点
   * @param from 开始位置（包含）
   * @param to 结束位置（不包含）
   * @return 区间内的克隆节点，完全不相交时返回 null
   */
  Node copy(Node node, int from, int to) {
    int start = starts.get(node);
    int end = ends.get(node);
    if (from >= to || end <= from || start >= to) return null;
    if (start >= from && end <= to) return node.clone();
    if (!(node instanceof Element element)) return null;
    Element clone = element.shallowClone();
    // 跨片段重建的祖先不再复制原 id，避免多个章节片段生成重复锚点。
    if (start < from) clone.removeAttr("id");
    for (Node child : node.childNodes()) {
      Node part = copy(child, from, to);
      if (part != null) clone.appendChild(part);
    }
    return clone.childNodeSize() == 0 ? null : clone;
  }
}
