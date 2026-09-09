package io.github.agilehub.epub2html;

import java.net.URI;
import java.nio.file.Paths;


/**
 * 归档路径工具，为 OPF、目录、样式和媒体处理提供一致的引用解析方式。
 *
 * <p>操作系统路径表示真实磁盘位置，归档路径表示 ZIP 内的位置，#fragment 表示 HTML 元素锚点。
 * 阅读本类时应区分这三者；本类整理归档引用，不负责最终 URL 编码或存储路径安全。
 */
final class EpubPaths {
  /** 仅提供静态方法，禁止创建没有用途的工具类实例。 */
  private EpubPaths() {}

  /**
   * 把文件内的相对引用转换为归档路径，并保留锚点片段。
   *
   * <p>例如 OPS/chapter.xhtml 引用 image/a.png 时得到 OPS/image/a.png；目录引用的 #part 用于后续定位标题。URI 解析失败时回退为基准目录与引用的拼接。此方法不是文件写入安全校验器。
   *
   * @param base 发起引用的文件路径，不是目录路径
   * @param reference href 或 src 中的原始引用
   * @return 经过 normalize 整理的路径，可带 #fragment
   * @throws java.nio.file.InvalidPathException 最终路径不符合宿主文件系统语法时抛出
   */
  static String resolve(String base, String reference) {
    // EPUB/URI 使用斜杠作为分隔符，先兼容输入中的 Windows 反斜杠。
    String clean = reference.replace('\\', '/');
    try {
      // base 是已解码的 ZIP 条目名，不是 URI 文本；组件构造器会正确转义空格和百分号。
      // reference 则是 XHTML 中的 URI 引用，保留其编码，避免把 %xx 再编码为 %25xx。
      URI baseUri = new URI(null, null, base.replace('\\', '/'), null);
      URI referenceUri = URI.create(clean);
      // 解析相对目录后只解码一次路径；不能用 URLDecoder，否则文件名中的 + 会变成空格。
      return normalize(
          baseUri.resolve(referenceUri).getPath()
              + (referenceUri.getFragment() == null
                  ? ""
                  : "#" + referenceUri.getFragment()));
    } catch (Exception e) {
      // 无法按 URI 解析时，使用基准文件的所在目录进行字符串拼接。
      int slash = base.lastIndexOf('/');
      return normalize((slash < 0 ? "" : base.substring(0, slash + 1)) + clean);
    }
  }


  static String normalize(String path) {
    // # 后的文字是 HTML 锚点，不应被文件系统当作文件名参与路径归一化。
    String fragment = "";
    int hash = path.indexOf('#');
    if (hash >= 0) {
      fragment = path.substring(hash);
      path = path.substring(0, hash);
    }
    // 保留原算法：它整理点段，但不能代替本地保存时的目标目录越界检查。
    return Paths.get(path).normalize().toString().replace('\\', '/').replaceFirst("^/", "")
        + fragment;
  }


  /**
   * 从目录目标中取出文件部分，供标题处理器匹配章节。
   *
   * @param target 完整目录目标，例如 OPS/a.xhtml#intro
   * @return 第一个 # 之前的部分；没有 # 时返回原字符串
   */
  static String targetFile(String target) {
    int hash = target.indexOf('#');
    return hash < 0 ? target : target.substring(0, hash);
  }


  /**
   * 取出目录目标中的锚点名称，供 getElementById 定位。
   *
   * @param target 完整目录目标
   * @return # 后的文本；没有 # 时为 null，末尾为 # 时为空字符串
   */
  static String targetFragment(String target) {
    int hash = target.indexOf('#');
    return hash < 0 ? null : target.substring(hash + 1);
  }


  /**
   * 判断媒体引用是否应跳过 ZIP 内查找。
   *
   * <p>data:、纯片段和带 URI scheme 的地址会跳过；//host/path 这种无 scheme 的地址目前不被本方法识别为外部地址。
   *
   * @param value 元素的非空 src 或 data 属性值
   * @return 应跳过时为 true，否则为 false
   * @throws IllegalArgumentException 地址不是合法 URI 且未命中前两个前缀分支时抛出
   */
  static boolean isExternal(String value) {
    return value.startsWith("data:") || value.startsWith("#") || URI.create(value).isAbsolute();
  }

}
