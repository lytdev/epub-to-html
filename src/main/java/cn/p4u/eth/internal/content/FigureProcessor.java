package cn.p4u.eth.internal.content;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/** 将图片与紧邻的图注组合成 figure，在章节片段内部处理，不跨章节移动内容。 样式与资源已处理完毕；转换后再由内容读取器按选项清理 class 和 id。 */
final class FigureProcessor {
  /** 支持阿拉伯数字及全角数字，允许“图”和编号之间有排版空白。 */
  private static final Pattern CAPTION = Pattern.compile("^图[\\s\\p{Z}]*[0-9０-９]");

  /** 仅把常见文本容器当作图注，避免将章节标题等结构元素改名。 */
  private static final Set<String> TEXT_CONTAINERS = Set.of("p", "div", "span", "section");

  private FigureProcessor() {}

  /**
   * 原地组合图片与图注，保留图片属性及图注容器属性，图注内部仅保留文本。
   *
   * @param document 已完成样式和媒体替换的章节片段，原正文标题保留
   */
  static void wrapCaptions(Document document) {
    // 快照避免移动节点影响遍历；已有 figure 不重复包装。
    for (Element image : List.copyOf(document.select("img"))) {
      if (image.closest("figure") != null) continue;
      Element anchor = image;
      Element parent = image.parent();
      // 兼容 <p><img></p><p>图6…</p>，仅允许不含其他正文的单图容器。
      if (parent != null
          && Set.of("p", "div", "section").contains(parent.normalName())
          && onlyContains(parent, image)) anchor = parent;
      Node next = nextContent(anchor);
      if (!(next instanceof Element caption) || !isCaption(caption)) continue;
      Element container = anchor.parent();
      if (container == null) continue;
      Element figure;
      if (anchor != image) {
        figure = anchor.tagName("figure");
      } else if (container.normalName().equals("p")) {
        // figure 不能嵌入 p；只在整个段落恰好由图片和图注组成时替换段落。
        if (!onlyContains(container, image, caption)) continue;
        figure = container.tagName("figure");
      } else {
        figure = new Element("figure");
        // 新包装不引入浏览器默认 figure 外边距，原图注的 margin 则保持不变。
        figure.attr("style", "margin: 0");
        image.before(figure);
        figure.appendChild(image);
      }
      // 复用图注容器的 style/id；内部标签在下面统一转成文本。
      caption.tagName("figcaption");
      figure.appendChild(caption);
    }
    // 同时处理 EPUB 原有的 figcaption，确保其中不再嵌套 p、span 或链接。
    // text(String) 会安全转义文本中的 < 和 &，不会将图注文字重新解析成标签。
    for (Element caption : document.select("figcaption")) {
      caption.text(caption.text());
    }
  }

  /**
   * 返回紧邻的有效节点，只忽略格式化空白和注释，不跨过真实正文。
   *
   * @param node 图片或单图容器
   * @return 后继节点；不存在时返回 null
   */
  private static Node nextContent(Node node) {
    Node next = node.nextSibling();
    while (ignorable(next)) next = next.nextSibling();
    return next;
  }

  /**
   * 判断容器是否只含指定节点及排版空白。
   *
   * @param parent 待检查的容器
   * @param allowed 允许移动的直接子节点
   * @return 不会夹带其他正文时为 true
   */
  private static boolean onlyContains(Element parent, Node... allowed) {
    List<Node> nodes = List.of(allowed);
    return parent.childNodes().stream().allMatch(n -> nodes.contains(n) || ignorable(n));
  }

  /**
   * @param node 待检查节点
   * @return 是否是可忽略的空白或注释
   */
  private static boolean ignorable(Node node) {
    return node instanceof Comment || node instanceof TextNode text && text.isBlank();
  }

  /**
   * @param element 紧邻图片的元素
   * @return 是否为“图＋数字”开头的纯文本或行内排版容器
   */
  private static boolean isCaption(Element element) {
    if (!TEXT_CONTAINERS.contains(element.normalName())
        || !CAPTION.matcher(element.text().strip()).find()) return false;
    // 排除含图片、视频或块级正文的容器，避免误吞下一整个章节。
    for (Element child : element.getAllElements()) {
      if (child == element) continue;
      if (child.isBlock()
          || Set.of("img", "svg", "video", "audio", "iframe", "object", "embed")
              .contains(child.normalName())) return false;
    }
    return true;
  }
}
