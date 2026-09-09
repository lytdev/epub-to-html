package io.github.agilehub.epub2html;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class EpubConverterTest {

  @TempDir Path temporaryDirectory;

  @Test
  void convertsProvidedDemoEpub() throws Exception {
    String dir = "E:\\_tmp\\epub\\";
    Path epub = Path.of(dir + "demo2.epub");
    assertTrue(java.nio.file.Files.exists(epub), "demo.epub must be available at project root");
    var result =
        new EpubConverter().convert(epub, new LocalResourceHandler(temporaryDirectory, "media"));
    String html = TocTestSupport.content(result);
    assertFalse(result.getFirst().getLabel().isBlank());
    assertTrue(html.contains("style="));
    assertFalse(result.isEmpty());
    try (var files = java.nio.file.Files.walk(temporaryDirectory)) {
      assertTrue(files.anyMatch(java.nio.file.Files::isRegularFile));
    }
  }

  @Test
  void delegatesResourcesAndUsesHandlerUrl() throws Exception {
    String dir = "E:\\_tmp\\epub\\";
    Path epub = Path.of(dir + "demo1.epub");
    Path htmlPath = Path.of(dir + "demo1.html");
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
    Files.writeString(htmlPath, html);
  }
}
