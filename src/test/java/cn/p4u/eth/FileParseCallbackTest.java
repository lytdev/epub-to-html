package cn.p4u.eth;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证 FileParseCallback 的逐项进度、完成与异常通知，以及 null 回调与无回调的一致性。 */
class FileParseCallbackTest {
  @TempDir Path directory;

  @Test
  void reportsProgressPerTopLevelItemInOrderAndCompletes() throws Exception {
    Path epub = archive(Map.of(
        "META-INF/container.xml", "<container><rootfile full-path='OPS/book.opf'/></container>",
        "OPS/book.opf", """
            <package><manifest>
              <item id="one" href="one.xhtml"/>
              <item id="two" href="two.xhtml"/>
              <item id="toc" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
            </manifest><spine><itemref idref="one"/><itemref idref="two"/></spine></package>
            """,
        "OPS/toc.ncx", """
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap>
              <navPoint><navLabel><text>第一章</text></navLabel><content src="one.xhtml"/></navPoint>
              <navPoint><navLabel><text>第二章</text></navLabel><content src="two.xhtml"/></navPoint>
            </navMap></ncx>
            """,
        "OPS/one.xhtml", "<html><head/><body><p>正文一</p></body></html>",
        "OPS/two.xhtml", "<html><head/><body><p>正文二</p></body></html>"));

    List<Integer> counts = new ArrayList<>();
    List<Integer> totals = new ArrayList<>();
    List<String> labels = new ArrayList<>();
    List<String> targets = new ArrayList<>();
    List<String> contents = new ArrayList<>();
    List<String> completed = new ArrayList<>();

    var result = new EpubConverter().convert(
        epub, resource -> "unused", false, new FileParseCallback<TocItem>() {
          @Override
          public void onLineParsed(int count, int total, CallBackRecord<TocItem> record) {
            counts.add(count);
            totals.add(total);
            labels.add(record.label());
            targets.add(record.target());
            contents.add(record.content());
          }

          @Override
          public void onComplete(int total, String message) {
            completed.add(total + ":" + message);
          }
        });

    assertEquals(List.of(1, 2), counts);
    assertEquals(List.of(2, 2), totals);
    assertEquals(List.of("第一章", "第二章"), labels);
    assertEquals(List.of("OPS/one.xhtml", "OPS/two.xhtml"), targets);
    assertTrue(contents.get(0).contains("正文一"));
    assertTrue(contents.get(1).contains("正文二"));
    assertEquals(List.of("2:文件处理完成"), completed);
    assertEquals(2, result.size());
  }

  @Test
  void reportsErrorWithCurrentLineAndPropagatesOnContentFailure() throws Exception {
    Path epub = archive(
        Map.of(
            "META-INF/container.xml", "<container><rootfile full-path='OPS/book.opf'/></container>",
            "OPS/book.opf", """
                <package><manifest>
                  <item id="one" href="one.xhtml"/>
                  <item id="two" href="two.xhtml"/>
                  <item id="toc" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                </manifest><spine><itemref idref="one"/><itemref idref="two"/></spine></package>
                """,
            "OPS/toc.ncx", """
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap>
                  <navPoint><navLabel><text>第一章</text></navLabel><content src="one.xhtml"/></navPoint>
                  <navPoint><navLabel><text>第二章</text></navLabel><content src="two.xhtml"/></navPoint>
                </navMap></ncx>
                """,
            "OPS/one.xhtml", "<html><head/><body><img src='one.png'/></body></html>",
            "OPS/two.xhtml", "<html><head/><body><img src='two.png'/></body></html>"),
        Map.of("OPS/one.png", new byte[] {1}, "OPS/two.png", new byte[] {2}));

    IOException failure = new IOException("upload failed");
    int[] errorLine = {-1};
    Exception[] error = {null};

    IOException actual = assertThrows(IOException.class,
        () -> new EpubConverter().convert(epub, resource -> {
          if (resource.archivePath().endsWith("two.png")) {
            throw failure;
          }
          return "https://cdn.example/" + resource.archivePath();
        }, false, new FileParseCallback<TocItem>() {
          @Override
          public void onLineParsed(int count, int total, CallBackRecord<TocItem> record) {}

          @Override
          public void onError(Exception ex, int line) {
            error[0] = ex;
            errorLine[0] = line;
          }
        }));

    assertSame(failure, actual);
    assertSame(failure, error[0]);
    assertEquals(2, errorLine[0]);
  }

  @Test
  void reportsErrorWithZeroForDecompressionFailure() {
    int[] errorLine = {-1};
    assertThrows(IOException.class,
        () -> new EpubConverter().convert(
            new ByteArrayInputStream(new byte[0]), resource -> "unused", false,
            new FileParseCallback<TocItem>() {
              @Override public void onLineParsed(int count, int total, CallBackRecord<TocItem> record) {}

              @Override public void onError(Exception ex, int line) {
                errorLine[0] = line;
              }
            }));
    assertEquals(0, errorLine[0]);
  }

  @Test
  void excludesGroupingNodesFromProgress() throws Exception {
    Path epub = archive(Map.of("a.xhtml", "<html><body><p>唯一正文</p></body></html>"));
    TocItem group = new TocItem(null, 1, "分组");
    group.setChildren(List.of(new TocItem("a.xhtml", 2, "子节")));

    List<Integer> counts = new ArrayList<>();
    try (var zip = new java.util.zip.ZipFile(epub.toFile())) {
      int total = new TocContentReader().read(zip, List.of(group), r -> "unused",
          new LinkedHashMap<>(), new FileParseCallback<TocItem>() {
            @Override public void onLineParsed(int count, int total, CallBackRecord<TocItem> record) {
              counts.add(count);
            }
          });
      assertEquals(0, total);
    }
    assertTrue(counts.isEmpty());
  }

  @Test
  void nullCallbackBehavesLikeNoCallback() throws Exception {
    Path epub = archive(Map.of(
        "META-INF/container.xml", "<container><rootfile full-path='OPS/book.opf'/></container>",
        "OPS/book.opf", """
            <package><manifest>
              <item id="one" href="one.xhtml"/>
              <item id="toc" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
            </manifest><spine><itemref idref="one"/></spine></package>
            """,
        "OPS/toc.ncx", """
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap>
              <navPoint><navLabel><text>第一章</text></navLabel><content src="one.xhtml"/></navPoint>
            </navMap></ncx>
            """,
        "OPS/one.xhtml", "<html><head/><body><p>正文一</p></body></html>"));
    EpubConverter converter = new EpubConverter();
    var expected = converter.convert(epub, resource -> "unused");
    var withNull = converter.convert(epub, resource -> "unused", false, null);
    TocTestSupport.assertTreeEquals(expected, withNull);
  }

  private Path archive(Map<String, String> textEntries) throws IOException {
    return archive(textEntries, Map.of());
  }

  private Path archive(Map<String, String> textEntries, Map<String, byte[]> binaryEntries)
      throws IOException {
    Path path = directory.resolve("book.epub");
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
      for (var entry : textEntries.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
      for (var entry : binaryEntries.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue());
        zip.closeEntry();
      }
    }
    return path;
  }
}
