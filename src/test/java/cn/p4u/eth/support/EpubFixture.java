package cn.p4u.eth.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 测试数据工厂：构造可阅读的最小 EPUB，不依赖作者电脑上的文件或未提交的大书。 */
public final class EpubFixture {
  public static final String CHAPTER = "OEBPS/中华优秀传统文化概论 - 副本.xhtml";
  public static final String IMAGE = "OEBPS/image/1.徽韵悠长：探索徽文化_fmt.png";

  private EpubFixture() {}

  public static Path create(Path directory) throws IOException {
    Path epub = directory.resolve("sample.epub");
    Map<String, String> entries = Map.of(
        "META-INF/container.xml", """
            <container><rootfiles><rootfile full-path="OEBPS/book.opf"/></rootfiles></container>
            """,
        "OEBPS/book.opf", """
            <package><manifest>
              <item id="chapter" href="中华优秀传统文化概论 - 副本.xhtml"/>
              <item id="nav" href="nav.xhtml" properties="nav"/>
            </manifest><spine><itemref idref="chapter"/></spine></package>
            """,
        "OEBPS/nav.xhtml", """
            <html><body><nav epub:type="toc"><ol><li>
              <a href="中华优秀传统文化概论 - 副本.xhtml">示例章节</a>
            </li></ol></nav></body></html>
            """,
        CHAPTER, """
            <html><head><link rel="stylesheet" href="book.css"/></head><body>
            <p id="intro" class="x----2">示例正文</p>
            <img class="frame-8" src="image/1.%E5%BE%BD%E9%9F%B5%E6%82%A0%E9%95%BF%EF%BC%9A%E6%8E%A2%E7%B4%A2%E5%BE%BD%E6%96%87%E5%8C%96_fmt.png"/>
            <p>图1 示例图注</p></body></html>
            """,
        "OEBPS/book.css", "p {margin:0; padding:0; border-width:0;} p.x----2 {margin-bottom:23px;}");
    try (var zip = new ZipOutputStream(Files.newOutputStream(epub))) {
      for (var entry : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
      zip.putNextEntry(new ZipEntry(IMAGE));
      // 解析器只转交媒体字节，不解码图片；小型二进制即可验证资源契约。
      zip.write(new byte[] {1, 2, 3});
      zip.closeEntry();
    }
    return epub;
  }
}
