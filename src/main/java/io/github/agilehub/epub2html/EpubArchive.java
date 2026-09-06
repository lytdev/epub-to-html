package io.github.agilehub.epub2html;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;


/**
 * 归档文本读取工具，是包描述、导航和样式读取器共用的底层组件。
 *
 * <p>EPUB 可以理解为一个 ZIP 文件；ZipFile 表示整本书，ZipEntry 表示其中一个条目。
 * 本类负责找到条目或读取文本，整本书的打开与关闭由 {@link ConversionPipeline} 负责。
 */
final class EpubArchive {
  /** 仅提供静态方法，禁止创建没有用途的工具类实例。 */
  private EpubArchive() {}

  /**
   * 以 UTF-8 读取一个归档条目的完整文本。
   *
   * <p>供 OPF、NCX、XHTML 和 CSS 读取使用；此方法只关闭条目输入流，不关闭调用方传入的 ZipFile。二进制媒体不走这个文本解码方法。
   *
   * @param zip 已打开的 EPUB 归档
   * @param entry 要读取的条目，通常先由 requiredEntry 查找
   * @return 条目字节按 UTF-8 解码后的字符串
   * @throws IOException 无法打开或读取条目流时抛出
   */
  static String read(ZipFile zip, ZipEntry entry) throws IOException {
    // 条目流独立关闭，不影响流水线继续从同一个 ZIP 读取其他章节。
    try (InputStream in = zip.getInputStream(entry)) {
      // 一次读完并按 UTF-8 解码；此方法不根据 XML 声明自动切换编码。
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }


  /**
   * 查找转换所必需的条目，缺失时立即报告路径。
   *
   * <p>供章节和包描述读取使用；可选资源则由各处理器自行决定缺失时是否跳过。
   *
   * @param zip 已打开的 EPUB 归档
   * @param name ZIP 内完整条目名，例如 META-INF/container.xml
   * @return 找到的条目描述；不会返回 null，也不负责判断条目是否为目录
   * @throws IOException 指定条目不存在时抛出
   */
  static ZipEntry requiredEntry(ZipFile zip, String name) throws IOException {
    // ZipEntry 描述归档内文件；获取描述不等于已读取文件内容。
    ZipEntry entry = zip.getEntry(name);
    if (entry == null) throw new IOException("Missing EPUB entry: " + name);
    return entry;
  }

}
