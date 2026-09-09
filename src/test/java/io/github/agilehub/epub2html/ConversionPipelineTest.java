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
  void removalRunsAfterAnchorStylesAndHeadingsAndDoesNotLeakBetweenCalls() throws Exception {
    Path epub = fixture();
    EpubConverter converter = new EpubConverter();
    var retained = converter.convert(epub, resource -> "image.png");
    var explicitFalse = converter.convert(epub, resource -> "image.png", false);
    TocTestSupport.assertTreeEquals(retained, explicitFalse);
    var removed = converter.convert(epub, resource -> "image.png", true);
    var doc = Jsoup.parse(TocTestSupport.content(removed));
    assertTrue(doc.select("[class], [id]").isEmpty());
    assertEquals("第一章", removed.getFirst().getLabel());
    assertEquals("小节", removed.getFirst().getChildren().getFirst().getLabel());
    assertTrue(doc.select("h1,h2,h3,h4,h5,h6").isEmpty());
    assertTrue(doc.selectFirst("p").attr("style").contains("color: red"));
    assertEquals("image.png", doc.selectFirst("img").attr("src"));
    assertEquals("OPS/first.xhtml#sub", removed.getFirst().getChildren().getFirst().getTarget());
    assertFalse(Jsoup.parse(TocTestSupport.content(retained)).select("[class], [id]").isEmpty());
    // 同一转换器下一次调用仍采用默认 false，不应继承上一次的 true。
    TocTestSupport.assertTreeEquals(retained, converter.convert(epub, resource -> "image.png"));
    try (var input = Files.newInputStream(epub)) {
      TocTestSupport.assertTreeEquals(removed, converter.convert(input, resource -> "image.png", true));
    }
  }

  @Test
  void followsDirectoryTargetsAndKeepsHeadingsStylesAndResources() throws Exception {
    Path epub = fixture();
    AtomicInteger calls = new AtomicInteger();
    var result = new EpubConverter().convert(
        epub, resource -> {
          calls.incrementAndGet();
          assertEquals("OPS/picture.png", resource.archivePath());
          assertArrayEquals(new byte[] {1, 2, 3}, resource.content());
          return "https://cdn.example/test.png";
        });
    Document html = Jsoup.parse(TocTestSupport.content(result));
    assertTrue(html.select("section.epub-chapter, [data-epub-source]").isEmpty());
    assertEquals(java.util.List.of("p", "img"),
        html.body().children().stream().map(e -> e.tagName()).toList());
    // second.xhtml 未出现在目录，不再根据 spine 自动追加。
    assertEquals(java.util.List.of("正文一"), html.select("body > p").eachText());
    assertEquals("第一章", result.getFirst().getLabel());
    assertEquals("小节", result.getFirst().getChildren().getFirst().getLabel());
    assertTrue(html.select("h1,h2,h3,h4,h5,h6").isEmpty());
    assertEquals("sub", html.selectFirst("p").id());
    assertTrue(html.selectFirst("p.styled").attr("style").contains("color: red"));
    assertEquals(1, html.select("img[src='https://cdn.example/test.png']").size());
    assertEquals(1, calls.get());
    assertEquals(1, result.size());
    assertEquals(1, result.getFirst().getChildren().size());
    assertFalse(result.getFirst().getContent().contains("正文一"));
    assertTrue(result.getFirst().getChildren().getFirst().getContent().contains("正文一"));
    try (var files = Files.list(directory)) {
      assertEquals(java.util.List.of(epub), files.toList());
    }

    // 第二次调用仍须处理该资源，缓存不能泄漏到转换器或静态组件。
    new EpubConverter().convert(epub, resource -> {
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
        () -> new EpubConverter().convert(epub, resource -> { throw failure; }));
    assertSame(failure, actual);
    assertFalse(Files.exists(html));
  }

  @Test
  void rejectsBlankResourceUrl() throws Exception {
    Path epub = fixture();
    IOException failure = assertThrows(IOException.class,
        () -> new EpubConverter().convert(epub, resource -> " "));
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

  @Test
  void streamTreeMatchesPathAndLeavesCallerStreamOpen() throws Exception {
    Path epub = fixture();
    EpubConverter converter = new EpubConverter();
    var expected = converter.convert(epub, resource -> "https://cdn.example/image.png");
    AtomicInteger calls = new AtomicInteger();
    try (var input = new java.io.FilterInputStream(Files.newInputStream(epub)) {
      boolean closed;
      @Override public void close() throws IOException { closed = true; super.close(); }
      @Override public boolean markSupported() { return false; }
    }) {
      var actual = converter.convert(input, resource -> {
        calls.incrementAndGet();
        return "https://cdn.example/image.png";
      });
      TocTestSupport.assertTreeEquals(expected, actual);
      assertEquals(1, calls.get());
      assertFalse(input.closed);
      assertEquals(-1, input.read());
    }
  }

  @Test
  void streamFailurePreservesExceptionAndCallerOwnership() throws Exception {
    Path epub = fixture();
    IOException failure = new IOException("upload failed");
    try (var input = new java.io.FilterInputStream(Files.newInputStream(epub)) {
      boolean closed;
      @Override public void close() throws IOException { closed = true; super.close(); }
    }) {
      assertSame(failure, assertThrows(IOException.class,
          () -> new EpubConverter().convert(input, resource -> { throw failure; })));
      assertFalse(input.closed);
    }
  }

  @Test
  void rejectsInvalidStreamAndNullArguments() {
    EpubConverter converter = new EpubConverter();
    assertThrows(IOException.class, () -> converter.convert(
        new java.io.ByteArrayInputStream(new byte[0]), resource -> "image.png"));
    assertThrows(NullPointerException.class, () -> converter.convert(
        (java.io.InputStream) null, resource -> "image.png"));
    assertThrows(NullPointerException.class, () -> converter.convert(
        new java.io.ByteArrayInputStream(new byte[0]), (EpubResourceHandler) null));
  }

  @Test
  void temporaryFileIsRemovedWhenScopeFails() throws Exception {
    Path temporaryPath;
    try (TemporaryEpub temporary = TemporaryEpub.copyOf(
        new java.io.ByteArrayInputStream(new byte[] {1, 2, 3}))) {
      temporaryPath = temporary.path();
      assertTrue(Files.exists(temporaryPath));
      assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(temporaryPath));
    }
    assertFalse(Files.exists(temporaryPath));

    IOException failure = new IOException("conversion failed");
    TemporaryEpub temporary = TemporaryEpub.copyOf(
        new java.io.ByteArrayInputStream(new byte[0]));
    Path failedPath = temporary.path();
    assertSame(failure, assertThrows(IOException.class, () -> {
      try (temporary) { throw failure; }
    }));
    assertFalse(Files.exists(failedPath));
  }
}
