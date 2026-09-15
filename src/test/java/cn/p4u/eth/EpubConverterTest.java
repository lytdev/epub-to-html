package cn.p4u.eth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class EpubConverterTest {

  @TempDir Path temporaryDirectory;

  @Test
  void convertsProvidedDemoEpub() throws Exception {
    Path epub = EpubFixture.create(temporaryDirectory);
    var result =
        new EpubConverter().convert(epub, new LocalResourceHandler(temporaryDirectory.resolve("media"), "media"));
    String html = TocTestSupport.content(result);
    assertFalse(result.getFirst().getLabel().isBlank());
    assertTrue(html.contains("style="));
    assertFalse(result.isEmpty());
    try (var files = java.nio.file.Files.walk(temporaryDirectory.resolve("media"))) {
      assertTrue(files.anyMatch(java.nio.file.Files::isRegularFile));
    }
  }

  @Test
  void delegatesResourcesAndUsesHandlerUrl() throws Exception {
    Path epub = EpubFixture.create(temporaryDirectory);
    var result =
        new EpubConverter()
            .convert(
                epub,
                resource -> {
                  return "data:"
                      + resource.mediaType()
                      + ";base64,"
                      + Base64.getEncoder().encodeToString(resource.content());
                },
                true);

    String html = TocTestSupport.content(result);
    var document = org.jsoup.Jsoup.parse(html);
    assertEquals("data:image/png;base64,AQID", document.selectFirst("img").attr("src"));
    assertTrue(document.select("[class], [id]").isEmpty());
    assertEquals("图1 示例图注", document.selectFirst("figcaption").text());
  }
}
