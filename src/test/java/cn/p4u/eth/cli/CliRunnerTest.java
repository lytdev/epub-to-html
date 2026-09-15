package cn.p4u.eth.cli;

import cn.p4u.eth.model.TocItem;
import cn.p4u.eth.support.EpubFixture;
import cn.p4u.eth.util.TocSupport;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 端到端验证命令行参数、输出文件和 Linux 可用的退出码约定。 */
class CliRunnerTest {
  @TempDir Path directory;

  @Test
  void convertsEpubToJsonAndAssets() throws Exception {
    Path epub = EpubFixture.create(directory);
    Path output = directory.resolve("result");
    var stdout = new ByteArrayOutputStream();
    var stderr = new ByteArrayOutputStream();

    int exitCode = CliRunner.run(
        new String[] {"-i", epub.toString(), "-o", output.toString(), "-f", "json", "-d"},
        new PrintStream(stdout, true, StandardCharsets.UTF_8),
        new PrintStream(stderr, true, StandardCharsets.UTF_8));

    assertEquals(0, exitCode, stderr.toString(StandardCharsets.UTF_8));
    String json = Files.readString(output.resolve("chapters.json"));
    assertTrue(json.startsWith("[{") && json.endsWith("]\n"));
    assertTrue(json.contains("\"label\":\"示例章节\""));
    assertTrue(json.contains("assets/OEBPS/image/1.徽韵悠长：探索徽文化_fmt.png"));
    assertFalse(json.contains("class=\\\""));
    assertArrayEquals(new byte[] {1, 2, 3},
        Files.readAllBytes(output.resolve("assets").resolve(EpubFixture.IMAGE)));
    assertTrue(stderr.toString(StandardCharsets.UTF_8).isEmpty());
  }

  @Test
  void convertsEpubToBase64Html() throws Exception {
    Path epub = EpubFixture.create(directory);
    Path output = directory.resolve("embedded.html");
    var stdout = new ByteArrayOutputStream();
    var stderr = new ByteArrayOutputStream();

    int exitCode = CliRunner.run(
        new String[] {"-i", epub.toString(), "-o", output.toString(), "-f", "html", "-m", "base64"},
        new PrintStream(stdout, true, StandardCharsets.UTF_8),
        new PrintStream(stderr, true, StandardCharsets.UTF_8));

    assertEquals(0, exitCode, stderr.toString(StandardCharsets.UTF_8));
    String html = Files.readString(output);
    assertTrue(html.contains("data:image/png;base64,"));
    assertFalse(Files.exists(output.getParent().resolve("assets")));
    assertTrue(stdout.toString(StandardCharsets.UTF_8).contains("转换完成:"));
  }

  @Test
  void writesToInputDirectoryWhenOutputIsOmitted() throws Exception {
    Path epub = EpubFixture.create(directory);
    var stderr = new ByteArrayOutputStream();

    int exitCode = CliRunner.run(
        new String[] {"-i", epub.toString(), "-f", "json", "-m", "base64"},
        new PrintStream(new ByteArrayOutputStream()),
        new PrintStream(stderr));

    assertEquals(0, exitCode, stderr.toString(StandardCharsets.UTF_8));
    assertTrue(Files.isRegularFile(directory.resolve("chapters.json")));
  }

  @Test
  void reportsHelpAndInvalidArguments() {
    var output = new ByteArrayOutputStream();
    var error = new ByteArrayOutputStream();
    assertEquals(0, CliRunner.run(new String[] {"--help"},
        new PrintStream(output), new PrintStream(error)));
    assertTrue(output.toString().contains("java -jar"));
    assertEquals(2, CliRunner.run(new String[] {"--unknown"},
        new PrintStream(output), new PrintStream(error)));
    assertTrue(error.toString().contains("未知参数"));
    assertEquals(2, CliRunner.run(new String[] {"-f", "xml"},
        new PrintStream(output), new PrintStream(error)));
    assertTrue(error.toString().contains("html 或 json"));
    assertEquals(2, CliRunner.run(new String[] {"-m", "oss"},
        new PrintStream(output), new PrintStream(error)));
    assertTrue(error.toString().contains("base64 或 local"));
  }

  @Test
  void rejectsMissingValuesAndValuesOnFlags() {
    assertUsageError(new String[] {"-i", "-f", "json"}, "-i 缺少值");
    assertUsageError(new String[] {"--format="}, "--format 缺少值");
    assertUsageError(new String[] {"--help=false"}, "--help 不接收值");
    assertUsageError(new String[] {"--delete-class=true"}, "--delete-class 不接收值");
  }

  @Test
  void refusesToOverwriteInputOrUseMismatchedExtension() throws Exception {
    Path epub = EpubFixture.create(directory);
    assertUsageError(
        new String[] {"-i", epub.toString(), "-o", epub.toString()}, "不能覆盖输入 EPUB");
    assertUsageError(
        new String[] {"-i", epub.toString(), "-o", directory.resolve("out.json").toString()},
        "扩展名与 --format html 不一致");
  }

  @Test
  void escapesHtmlLabelsAndAcceptsNullClearTitleFlag() {
    TocItem item = new TocItem(null, 1, "<章节 & 说明>");
    item.setContent("<p>正文</p>");

    String html = TocSupport.contentToHtml(java.util.List.of(item), null);

    assertTrue(html.startsWith("<h1>&lt;章节 &amp; 说明&gt;</h1>"));
    assertFalse(html.contains("<章节"));
  }

  @Test
  void escapesJsonControlCharacters() {
    TocItem item = new TocItem(null, 1, "引号\"与换行\n");
    item.setContent("反斜杠\\\t");
    String json = TocSupport.writeToJson(java.util.List.of(item));
    assertTrue(json.contains("引号\\\"与换行\\n"));
    assertTrue(json.contains("反斜杠\\\\\\t"));
  }

  private static void assertUsageError(String[] args, String expectedMessage) {
    var stderr = new ByteArrayOutputStream();
    int exitCode = CliRunner.run(
        args, new PrintStream(new ByteArrayOutputStream()), new PrintStream(stderr));
    assertEquals(2, exitCode);
    assertTrue(stderr.toString().contains(expectedMessage), stderr.toString());
  }
}
