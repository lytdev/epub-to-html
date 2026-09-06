package io.github.agilehub.epub2html;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class EpubConverterTest {

  @TempDir Path temporaryDirectory;

  @Test
  void convertsProvidedDemoEpub() throws Exception {
    Path epub = Path.of("demo.epub");
    assertTrue(java.nio.file.Files.exists(epub), "demo.epub must be available at project root");
    ConversionResult result =
        new EpubConverter()
            .convert(
                epub, temporaryDirectory.resolve("demo.html"), temporaryDirectory.resolve("media"));
    String html = java.nio.file.Files.readString(result.htmlFile());
    assertTrue(html.contains("<h1") || html.contains("<h2"));
    assertTrue(html.contains("style="));
    assertFalse(result.copiedMedia().isEmpty());
  }

  @Test
  void delegatesResourcesAndUsesHandlerUrl() throws Exception {
    Path epub = Path.of("demo.epub");
    ConversionResult result =
        new EpubConverter()
            .convert(
                epub,
                temporaryDirectory.resolve("embedded.html"),
                resource -> {
                  return "data:"
                      + resource.mediaType()
                      + ";base64,"
                      + Base64.getEncoder().encodeToString(resource.content());
                });

    String html = java.nio.file.Files.readString(result.htmlFile());
    assertNull(result.mediaDirectory());
    assertFalse(result.copiedMedia().isEmpty());
    assertTrue(html.contains("src=\"data:image/"));
  }
}
