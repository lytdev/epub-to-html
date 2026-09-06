package io.github.agilehub.epub2html;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;

/**
 * HTML 组装器，收集所有已处理章节并写出最终文档。
 *
 * <p>{@link ConversionPipeline} 为每次转换创建一个实例，按阅读顺序调用 append，最后调用 write。
 * 它持有可变 DOM，所以只用于当前转换；不同转换不能共享同一个实例。
 */
final class HtmlAssembler {
  /** 最终输出 DOM，每个组装器实例独立持有，不能跨转换共享。 */
  private final Document output = Document.createShell("");

  /** 创建包含 head/body 的空文档，固定输出为 UTF-8 HTML。 */
  HtmlAssembler() {
    // 原章节以 XML 模式读取，输出则使用 HTML 序列化规则。
    output.outputSettings()
        .syntax(Document.OutputSettings.Syntax.html)
        .charset(StandardCharsets.UTF_8)
        .prettyPrint(true);
    output.head().appendElement("meta").attr("charset", "UTF-8");
  }

  /**
   * 把处理完成的章节正文移动到最终 HTML 的独立 section 中。
   *
   * <p>只合并 body 的子节点，不复制章节 head 或 body 自身属性。appendChild 会移动节点，传入 document 的 body 会失去被移走的节点。
   *
   * @param chapter 章节归档路径，写入 data-epub-source 便于追踪
   * @param document 已完成样式、标题及媒体处理的章节文档
   */
  void append(String chapter, Document document) {
    // 给每章增加容器并记录来源，以便排查某段内容来自哪个 EPUB 文件。
    Element section = output.body().appendElement("section")
        .addClass("epub-chapter")
        .attr("data-epub-source", chapter);
    Element body = document.body();
    if (body != null) {
      // 先取快照再移动节点，否则原 body 的子节点列表会在遍历过程中改变。
      for (Node node : new ArrayList<>(body.childNodes())) {
        section.appendChild(node);
      }
    }
  }

  /**
   * 将已组装文档写成 UTF-8 HTML 文件。
   *
   * <p>由流水线在所有章节处理结束后调用；目标存在时覆盖，不采用临时文件原子替换，父目录需提前创建。
   *
   * @param target 输出文件路径
   * @throws IOException 文件无法创建或写入时抛出
   */
  void write(Path target) throws IOException {
    // 显式写入 doctype；此处直接写目标文件，不包含回滚或原子替换。
    Files.writeString(target, "<!doctype html>\n" + output.outerHtml(), StandardCharsets.UTF_8);
  }
}
