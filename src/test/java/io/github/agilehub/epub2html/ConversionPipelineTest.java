package io.github.agilehub.epub2html;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 使用小型多章节归档验证组件组合契约，避免仅依赖单页真实样例。 */
class ConversionPipelineTest {
  @TempDir Path directory;

  @Test
  void preservesSpineHeadingsStylesAndResourceDeduplication() throws Exception {
    Path epub = fixture();
    AtomicInteger calls = new AtomicInteger();
    ConversionResult result = new EpubConverter().convert(
        epub, directory.resolve("book.html"), resource -> {
          calls.incrementAndGet();
          assertEquals("OPS/picture.png", resource.archivePath());
          assertArrayEquals(new byte[] {1, 2, 3}, resource.content());
          return "https://cdn.example/test.png";
        });
    Document html = Jsoup.parse(Files.readString(result.htmlFile()));
    assertEquals(2, html.select("section.epub-chapter").size());
    assertEquals("OPS/first.xhtml", html.select("section").first().attr("data-epub-source"));
    assertEquals("第一章", html.selectFirst("h1").text());
    assertEquals("小节", html.selectFirst("h2").text());
    assertEquals("sub", html.selectFirst("h2").nextElementSibling().id());
    assertEquals("color: red", html.selectFirst("p.styled").attr("style"));
    assertEquals(2, html.select("img[src='https://cdn.example/test.png']").size());
    assertEquals(1, calls.get());
    assertEquals(java.util.List.of("OPS/picture.png"), result.copiedMedia());
    assertEquals(2, result.tocEntries());

    // 第二次调用仍须处理该资源，缓存不能泄漏到转换器或静态组件。
    new EpubConverter().convert(epub, directory.resolve("again.html"), resource -> {
      calls.incrementAndGet();
      return "another.png";
    });
    assertEquals(2, calls.get());
  }

  @Test
  void propagatesHandlerFailureWithoutPublishingHtml() throws Exception {
    Path epub = fixture();
    Path html = directory.resolve("failed.html");
    IOException failure = new IOException("upload failed");
    IOException actual = assertThrows(IOException.class,
        () -> new EpubConverter().convert(epub, html, resource -> { throw failure; }));
    assertSame(failure, actual);
    assertFalse(Files.exists(html));
  }

  @Test
  void rejectsBlankResourceUrl() throws Exception {
    Path epub = fixture();
    IOException failure = assertThrows(IOException.class,
        () -> new EpubConverter().convert(epub, directory.resolve("blank.html"), resource -> " "));
    assertTrue(failure.getMessage().contains("OPS/picture.png"));
  }

  private Path fixture() throws IOException {
    Map<String, String> entries = new LinkedHashMap<>();
    entries.put("META-INF/container.xml",
        "<container><rootfiles><rootfile full-path='OPS/book.opf'/></rootfiles></container>");
    entries.put("OPS/book.opf", """
        <package><manifest>
          <item id="second" href="second.xhtml"/>
          <item id="first" href="first.xhtml"/>
          <item id="toc" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
        </manifest><spine><itemref idref="first"/><itemref idref="second"/></spine></package>
        """);
    entries.put("OPS/toc.ncx", """
        <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap>
          <navPoint><navLabel><text>第一章</text></navLabel><content src="first.xhtml"/>
            <navPoint><navLabel><text>小节</text></navLabel><content src="first.xhtml#sub"/></navPoint>
          </navPoint>
        </navMap></ncx>
        """);
    entries.put("OPS/first.xhtml", """
        <html><head><link rel="stylesheet" href="book.css"/></head><body>
          <p id="sub" class="styled">正文一</p><img src="picture.png"/>
        </body></html>
        """);
    entries.put("OPS/second.xhtml",
        "<html><head/><body><p>正文二</p><img src='picture.png'/></body></html>");
    entries.put("OPS/book.css", "p.styled { color: red; }");
    Path epub = directory.resolve("fixture.epub");
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(epub))) {
      for (var entry : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
      zip.putNextEntry(new ZipEntry("OPS/picture.png"));
      zip.write(new byte[] {1, 2, 3});
      zip.closeEntry();
    }
    return epub;
  }
}
