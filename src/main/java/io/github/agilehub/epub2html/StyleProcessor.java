package io.github.agilehub.epub2html;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import static io.github.agilehub.epub2html.EpubArchive.read;
import static io.github.agilehub.epub2html.EpubArchive.requiredEntry;
import static io.github.agilehub.epub2html.EpubPaths.resolve;

/**
 * 样式处理器，把 EPUB 中链接的 CSS 规则尽量转换为元素的内联 style。
 *
 * <p>由流水线在标题和媒体处理前调用。CSS 选择器描述“哪些元素受影响”，声明描述“设置哪些属性”。
 * 本类先收集文本，再拆分规则，最后匹配 DOM。其简化算法不等同于浏览器完整 CSS 计算。
 */
final class StyleProcessor {
  /** 仅提供静态方法，禁止创建没有用途的工具类实例。 */
  private StyleProcessor() {}

  /**
   * 单条简化样式规则，仅用于 StyleProcessor 内部。
   *
   * @param selector 用于匹配 DOM 元素的单个选择器，例如 p.note
   * @param declarations 大括号内的属性声明，例如 color: red
   */
  private record CssRule(String selector, String declarations) {
    /**
     * 将简单 CSS 规则展开成单选择器的规则列表。
     *
     * <p>逗号连接的选择器分别生成规则；使用正则和字符串拆分，不是完整 CSS 语法解析器，不可靠支持嵌套规则或选择器内部逗号。
     *
     * @param css 合并后的 CSS 文本
     * @return 按扫描顺序排列的规则列表
     */
    static List<CssRule> parse(String css) {
      ArrayList<CssRule> result = new ArrayList<>();
      // 先移除 CSS 注释，再提取不含嵌套大括号的规则；不是完整的 CSS 语法分析。
      java.util.regex.Matcher m =
          java.util.regex.Pattern.compile("(?s)([^{}@]+)\\{([^{}]*)}")
              .matcher(css.replaceAll("(?s)/\\*.*?\\*/", ""));
      // 一个规则写成 p, div 时，拆为两条规则，后续分别查询 DOM。
      while (m.find())
        for (String selector : m.group(1).split(",")) {
          String s = selector.trim();
          if (!s.isEmpty() && !s.startsWith("@")) result.add(new CssRule(s, m.group(2).trim()));
        }
      return result;
    }
  }

  /**
   * 读取章节外部样式并写入匹配元素的 style 属性。
   *
   * <p>由流水线先于标题处理调用。仅收集 link 引用的样式，不处理 head 中的 style 标签；现有实现会删除所有带 href 的 link，包括非样式链接。
   *
   * @param zip EPUB 归档，供 CSS 读取使用
   * @param doc 待原地修改的章节 DOM
   * @param documentPath 当前章节路径，用于解析样式表 href
   * @throws IOException 样式表存在但读取失败时抛出
   */
  static void inlineStyles(ZipFile zip, Document doc, String documentPath)
      throws IOException {
    // 收集样式表路径。当前实现随后移除所有带 href 的 link。
    List<String> cssFiles = new ArrayList<>();
    // 用列表快照遍历，因为循环内会从 DOM 删除 link 节点。
    for (Element link : new ArrayList<>(doc.select("link[href]"))) {
      if (link.attr("rel").toLowerCase(Locale.ROOT).contains("stylesheet"))
        cssFiles.add(resolve(documentPath, link.attr("href")));
      link.remove();
    }
    // 汇总外部样式文本；每个顶层样式表有独立的循环导入检测集合。
    StringBuilder css = new StringBuilder();
    for (String file : cssFiles) appendCss(zip, file, css, new HashSet<>());
    // 按扫描顺序应用规则。匹配后写入 style，而非计算浏览器最终渲染样式。
    for (CssRule rule : CssRule.parse(css.toString())) {
      try {
        for (Element element : doc.select(rule.selector()))
          element.attr("style", mergeStyles(element.attr("style"), rule.declarations()));
      } catch (Exception ignored) {
        /* 不支持的选择器不阻断整本书转换。 */
      }
    }
  }


  /**
   * 先收集导入的 CSS，再追加当前样式表文本。
   *
   * <p>seen 防止同一次递归读取重复路径和循环导入；每个顶层样式表使用独立 seen。缺失条目直接跳过。
   *
   * @param zip 当前 EPUB 归档
   * @param path 当前 CSS 条目路径
   * @param target 累计 CSS 文本的缓冲区，本方法向其中追加
   * @param seen 本次顶层 CSS 收集已访问的路径集合，会被修改
   * @throws IOException 已存在的 CSS 条目读取失败时抛出
   */
  static void appendCss(ZipFile zip, String path, StringBuilder target, Set<String> seen)
      throws IOException {
    // Set.add 返回 false 说明已访问过；这可阻止 A 导入 B、B 又导入 A 的无限递归。
    if (!seen.add(path) || zip.getEntry(path) == null) return;
    String css = read(zip, requiredEntry(zip, path));
    // 导入路径相对于当前 CSS 文件，不能继续使用章节 XHTML 的目录作为基准。
    java.util.regex.Matcher imports =
        java.util.regex.Pattern.compile("@import\\s+(?:url\\()?['\"]?([^'\" )]+)").matcher(css);
    while (imports.find()) appendCss(zip, resolve(path, imports.group(1)), target, seen);
    // 先收集依赖，再追加当前文件；导入语句本身仍保留在文本中。
    target.append(css).append('\n');
  }


  /**
   * 合并规则声明与元素已有声明，返回新的 style 字符串。
   *
   * <p>已有 inline 同名属性覆盖 declarations；此前规则也已写入 inline，因此先应用的规则可能压过后续规则。本方法没有实现 CSS specificity 或 !important 优先级。
   *
   * @param inline 元素当前 style 属性，可为空字符串
   * @param declarations 当前规则的大括号内声明
   * @return 以分号和空格连接的属性声明
   */
  static String mergeStyles(String inline, String declarations) {
    LinkedHashMap<String, String> styles = new LinkedHashMap<>();
    // 先写新规则，再用元素已有 style 覆盖同名属性。
    addDeclarations(styles, declarations);
    addDeclarations(styles, inline);
    // 把属性映射重新拼接成 HTML style 属性使用的字符串。
    return styles.entrySet().stream()
        .map(e -> e.getKey() + ": " + e.getValue())
        .collect(java.util.stream.Collectors.joining("; "));
  }


  /**
   * 把声明字符串加入属性映射，同名属性以本次写入值覆盖。
   *
   * <p>按分号分段并以首个冒号拆分属性和值；无法正确处理字符串或 data URL 内部的分号，无有效冒号的片段被忽略。
   *
   * @param styles 要修改的属性名到属性值映射
   * @param source 待拆分的声明文本
   */
  static void addDeclarations(Map<String, String> styles, String source) {
    for (String declaration : source.split(";")) {
      // 只取第一个冒号分隔属性名与值；没有有效属性名的片段忽略。
      int colon = declaration.indexOf(':');
      if (colon > 0)
        styles.put(declaration.substring(0, colon).trim(), declaration.substring(colon + 1).trim());
    }
  }

}
