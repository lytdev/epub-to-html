package io.github.agilehub.epub2html;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.parser.Parser;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.net.URLConnection;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * EPUB 到单一 HTML 的转换入口。
 *
 * <p>该类面向需要在 Web 页面、内容审核或全文检索场景中消费 EPUB 的应用：它以 OPF spine
 * 作为正文顺序，以 NCX 或 EPUB 3 导航文档生成标题层级，并将外部 CSS 规则写入元素内联样式。
 * {@link EpubResourceHandler} 是资源存储的扩展点，因而调用方可以统一接入本地文件、对象存储或
 * Base64 策略。转换器不保存状态；同一实例可被多个请求并发调用。</p>
 */
public final class EpubConverter {
    private static final Set<String> MEDIA_TAGS = Set.of("img", "audio", "video", "source", "track", "object", "embed");

    /**
     * 创建无状态转换器。
     *
     * <p>通常可作为 Spring Bean 单例注入；资源的具体去向由每次 {@code convert} 调用传入的参数决定。</p>
     */
    public EpubConverter() {
    }

    /**
     * 转换 EPUB，并将媒体资源写入指定本地目录。
     *
     * <p>这是 {@link #convert(Path, Path, EpubResourceHandler)} 的本地文件便捷形式：内部创建
     * {@link LocalResourceHandler}，再将其返回的相对地址回写至 HTML。需要上传阿里云 OSS、添加鉴权
     * URL 或嵌入 Base64 时，应改用资源处理器重载。</p>
     *
     * @param epub 输入 EPUB 文件；必须是可读取的 ZIP 格式 EPUB
     * @param outputHtml 生成的单一 HTML 文件路径；其父目录会自动创建
     * @param mediaDirectory 媒体文件输出目录；HTML 中使用该目录的目录名作为资源 URL 前缀
     * @return 转换结果，{@link ConversionResult#mediaDirectory()} 为 {@code mediaDirectory}
     * @throws NullPointerException 当任一参数为 {@code null} 时抛出
     * @throws IOException 当 EPUB 无法读取、结构不完整、HTML 或资源无法写入时抛出
     */
    public ConversionResult convert(Path epub, Path outputHtml, Path mediaDirectory) throws IOException {
        Objects.requireNonNull(epub, "epub");
        Objects.requireNonNull(outputHtml, "outputHtml");
        Objects.requireNonNull(mediaDirectory, "mediaDirectory");
        createOutputParent(outputHtml);
        Files.createDirectories(mediaDirectory);
        String urlPrefix = mediaDirectory.getFileName().toString();
        ConversionResult result = convert(epub, outputHtml, new LocalResourceHandler(mediaDirectory, urlPrefix));
        return new ConversionResult(result.htmlFile(), mediaDirectory, result.copiedMedia(), result.tocEntries());
    }

    /**
     * 转换 EPUB，并将每个不同的媒体资源委托给调用方处理。
     *
     * <p>该方法是完整转换链路的编排入口：读取包描述与目录、按 spine 合并章节、内联 CSS、调用
     * {@code resourceHandler} 处理图片/音视频等资源，最后写出 HTML。处理器返回的非空 URL 会替换
     * 原元素的 {@code src} 或 {@code data} 属性。同一路径的资源在一次转换中只交给处理器一次，
     * 因此适合对接对象存储，避免重复上传。</p>
     *
     * @param epub 输入 EPUB 文件；必须包含有效的 {@code META-INF/container.xml} 与 OPF spine
     * @param outputHtml 生成的单一 HTML 文件路径；其父目录会自动创建
     * @param resourceHandler 资源处理策略；必须同步消费资源字节并返回可用于 HTML 的非空 URL
     * @return 转换结果；使用自定义处理器时 {@link ConversionResult#mediaDirectory()} 为 {@code null}
     * @throws NullPointerException 当任一参数为 {@code null} 时抛出
     * @throws IOException 当 EPUB 解析失败、资源处理器失败或输出文件无法写入时抛出
     */
    public ConversionResult convert(Path epub, Path outputHtml, EpubResourceHandler resourceHandler) throws IOException {
        Objects.requireNonNull(epub, "epub");
        Objects.requireNonNull(outputHtml, "outputHtml");
        Objects.requireNonNull(resourceHandler, "resourceHandler");
        createOutputParent(outputHtml);

        try (ZipFile zip = new ZipFile(epub.toFile(), StandardCharsets.UTF_8)) {
            String opfPath = packagePath(zip);
            PackageData pkg = readPackage(zip, opfPath);
            List<TocItem> toc = readToc(zip, pkg, opfPath);
            Map<String, List<TocItem>> tocByTarget = new LinkedHashMap<>();
            for (TocItem item : toc) tocByTarget.computeIfAbsent(item.target(), ignored -> new ArrayList<>()).add(item);

            Document output = Document.createShell("");
            output.outputSettings().syntax(Document.OutputSettings.Syntax.html).charset(StandardCharsets.UTF_8).prettyPrint(true);
            output.head().appendElement("meta").attr("charset", "UTF-8");
            Element body = output.body();
            LinkedHashMap<String, String> resourceUrls = new LinkedHashMap<>();
            for (String chapter : pkg.spine()) {
                ZipEntry entry = requiredEntry(zip, chapter);
                Document chapterDoc = Jsoup.parse(read(zip, entry), chapter, Parser.xmlParser());
                inlineStyles(zip, chapterDoc, chapter);
                injectTocHeadings(chapterDoc, chapter, tocByTarget);
                rewriteMedia(zip, chapterDoc, chapter, resourceHandler, resourceUrls);
                Element chapterContainer = body.appendElement("section").addClass("epub-chapter").attr("data-epub-source", chapter);
                Element sourceBody = chapterDoc.body();
                if (sourceBody != null) for (Node node : new ArrayList<>(sourceBody.childNodes())) chapterContainer.appendChild(node);
            }
            Files.writeString(outputHtml, "<!doctype html>\n" + output.outerHtml(), StandardCharsets.UTF_8);
            return new ConversionResult(outputHtml, null, List.copyOf(resourceUrls.keySet()), toc.size());
        }
    }

    /** 由 {@link #convert(Path, Path, EpubResourceHandler)} 调用，定位 OPF 包描述文件。 */
    private static String packagePath(ZipFile zip) throws IOException {
        ZipEntry container = requiredEntry(zip, "META-INF/container.xml");
        org.w3c.dom.Document xml = xml(read(zip, container));
        NodeList roots = xml.getElementsByTagNameNS("*", "rootfile");
        if (roots.getLength() == 0) throw new IOException("Invalid EPUB: META-INF/container.xml has no rootfile");
        return normalize(((org.w3c.dom.Element) roots.item(0)).getAttribute("full-path"));
    }

    /** 根据 OPF 清单和 spine 解析后续章节合并与目录读取所需的包信息。 */
    private static PackageData readPackage(ZipFile zip, String opfPath) throws IOException {
        org.w3c.dom.Document xml = xml(read(zip, requiredEntry(zip, opfPath)));
        Map<String, String> items = new HashMap<>();
        String ncx = null, nav = null;
        NodeList manifest = xml.getElementsByTagNameNS("*", "item");
        for (int i = 0; i < manifest.getLength(); i++) {
            org.w3c.dom.Element item = (org.w3c.dom.Element) manifest.item(i);
            String path = resolve(opfPath, item.getAttribute("href"));
            items.put(item.getAttribute("id"), path);
            String properties = item.getAttribute("properties");
            if ("application/x-dtbncx+xml".equals(item.getAttribute("media-type"))) ncx = path;
            if (Arrays.asList(properties.split("\\s+")).contains("nav")) nav = path;
        }
        ArrayList<String> spine = new ArrayList<>();
        NodeList refs = xml.getElementsByTagNameNS("*", "itemref");
        for (int i = 0; i < refs.getLength(); i++) {
            String path = items.get(((org.w3c.dom.Element) refs.item(i)).getAttribute("idref"));
            if (path != null) spine.add(path);
        }
        if (spine.isEmpty()) throw new IOException("Invalid EPUB: OPF spine is empty");
        return new PackageData(spine, ncx, nav);
    }

    /** 在章节合并前读取 NCX 或 EPUB 3 导航文档，供 {@link #injectTocHeadings} 建立标题层级。 */
    private static List<TocItem> readToc(ZipFile zip, PackageData pkg, String opf) throws IOException {
        if (pkg.ncx() != null && zip.getEntry(pkg.ncx()) != null) return readNcx(read(zip, requiredEntry(zip, pkg.ncx())), pkg.ncx());
        if (pkg.nav() != null && zip.getEntry(pkg.nav()) != null) return readNav(read(zip, requiredEntry(zip, pkg.nav())), pkg.nav());
        return List.of();
    }

    /** 将 NCX 导航树扁平化为带层级的目录项。 */
    private static List<TocItem> readNcx(String content, String ncxPath) throws IOException {
        org.w3c.dom.Document document = xml(content);
        ArrayList<TocItem> result = new ArrayList<>();
        NodeList maps = document.getElementsByTagNameNS("*", "navMap");
        if (maps.getLength() > 0) ncxItems(maps.item(0), ncxPath, 1, result);
        return result;
    }

    private static void ncxItems(org.w3c.dom.Node parent, String base, int level, List<TocItem> out) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            org.w3c.dom.Node node = children.item(i);
            if (!(node instanceof org.w3c.dom.Element e) || !"navPoint".equals(e.getLocalName())) continue;
            String label = textOf(e, "text");
            String src = attributeOf(e, "content", "src");
            if (src != null && !src.isBlank()) out.add(new TocItem(resolve(base, src), level, label));
            ncxItems(e, base, level + 1, out);
        }
    }

    /** 读取 EPUB 3 {@code nav.xhtml} 的有序列表，作为 NCX 不存在时的目录来源。 */
    private static List<TocItem> readNav(String content, String navPath) {
        Document doc = Jsoup.parse(content, navPath, Parser.xmlParser());
        Element nav = doc.selectFirst("nav[*|type=\"toc\"], nav[epub:type=\"toc\"], nav");
        if (nav == null) return List.of();
        ArrayList<TocItem> result = new ArrayList<>();
        navItems(nav.selectFirst("ol"), navPath, 1, result);
        return result;
    }

    private static void navItems(Element list, String base, int level, List<TocItem> out) {
        if (list == null) return;
        for (Element li : list.select("> li")) {
            Element link = li.selectFirst("> a[href]");
            if (link != null) out.add(new TocItem(resolve(base, link.attr("href")), level, link.text()));
            navItems(li.selectFirst("> ol"), base, level + 1, out);
        }
    }

    /** 在章节内容移入最终 HTML 前，将匹配该章节的目录项转换为 {@code h1} 至 {@code h6}。 */
    private static void injectTocHeadings(Document chapter, String chapterPath, Map<String, List<TocItem>> toc) {
        List<TocItem> chapterToc = new ArrayList<>();
        for (List<TocItem> items : toc.values()) for (TocItem item : items)
            if (targetFile(item.target()).equals(chapterPath)) chapterToc.add(item);
        if (chapterToc.isEmpty()) return;
        for (TocItem item : chapterToc) {
            String fragment = targetFragment(item.target());
            Element anchor = fragment == null ? chapter.body() : chapter.getElementById(fragment);
            if (anchor == null) continue;
            int level = Math.min(6, Math.max(1, item.level()));
            Element heading = chapter.createElement("h" + level).text(item.label());
            heading.attr("data-epub-toc-level", String.valueOf(item.level()));
            if (fragment != null) heading.attr("id", fragment + "-heading");
            // 未带片段的目录项指向整章；章节 body 的子节点才会被合并，标题必须插入其内部。
            if (fragment == null) anchor.prependChild(heading);
            else anchor.before(heading);
        }
    }

    /** 章节合并前将其链接 CSS（含 {@code @import}）应用为内联样式，避免输出 HTML 继续依赖 EPUB 内 CSS。 */
    private static void inlineStyles(ZipFile zip, Document doc, String documentPath) throws IOException {
        List<String> cssFiles = new ArrayList<>();
        for (Element link : new ArrayList<>(doc.select("link[href]"))) {
            if (link.attr("rel").toLowerCase(Locale.ROOT).contains("stylesheet")) cssFiles.add(resolve(documentPath, link.attr("href")));
            link.remove();
        }
        StringBuilder css = new StringBuilder();
        for (String file : cssFiles) appendCss(zip, file, css, new HashSet<>());
        for (CssRule rule : CssRule.parse(css.toString())) {
            try {
                for (Element element : doc.select(rule.selector())) element.attr("style", mergeStyles(element.attr("style"), rule.declarations()));
            } catch (Exception ignored) { /* 不支持的选择器不阻断整本书转换。 */ }
        }
    }

    /** 递归收集 CSS 与其导入文件；{@code seen} 防止循环导入。 */
    private static void appendCss(ZipFile zip, String path, StringBuilder target, Set<String> seen) throws IOException {
        if (!seen.add(path) || zip.getEntry(path) == null) return;
        String css = read(zip, requiredEntry(zip, path));
        java.util.regex.Matcher imports = java.util.regex.Pattern.compile("@import\\s+(?:url\\()?['\"]?([^'\" )]+)").matcher(css);
        while (imports.find()) appendCss(zip, resolve(path, imports.group(1)), target, seen);
        target.append(css).append('\n');
    }

    /**
     * 在章节移入结果文档前调用资源处理器，并回写元素引用。
     * {@code resourceUrls} 由转换入口跨章节共享，用于保证同一归档路径只处理一次。
     */
    private static void rewriteMedia(ZipFile zip, Document doc, String documentPath, EpubResourceHandler handler, Map<String, String> resourceUrls) throws IOException {
        for (Element element : doc.getAllElements()) {
            if (!MEDIA_TAGS.contains(element.tagName())) continue;
            String attribute = element.hasAttr("src") ? "src" : element.hasAttr("data") ? "data" : null;
            if (attribute == null || element.attr(attribute).isBlank() || isExternal(element.attr(attribute))) continue;
            String resource = resolve(documentPath, element.attr(attribute));
            ZipEntry entry = zip.getEntry(resource);
            if (entry == null || entry.isDirectory()) continue;
            String url = resourceUrls.get(resource);
            if (url == null) {
                byte[] content;
                try (InputStream in = zip.getInputStream(entry)) { content = in.readAllBytes(); }
                url = handler.handle(new EpubResource(resource, mediaType(resource), content));
                if (url == null || url.isBlank()) throw new IOException("Resource handler returned no URL for: " + resource);
                resourceUrls.put(resource, url);
            }
            element.attr(attribute, url);
        }
    }

    private static String mergeStyles(String inline, String declarations) {
        LinkedHashMap<String, String> styles = new LinkedHashMap<>();
        addDeclarations(styles, declarations); addDeclarations(styles, inline);
        return styles.entrySet().stream().map(e -> e.getKey() + ": " + e.getValue()).collect(java.util.stream.Collectors.joining("; "));
    }
    private static void addDeclarations(Map<String, String> styles, String source) {
        for (String declaration : source.split(";")) { int colon = declaration.indexOf(':'); if (colon > 0) styles.put(declaration.substring(0, colon).trim(), declaration.substring(colon + 1).trim()); }
    }
    private static String read(ZipFile zip, ZipEntry entry) throws IOException { try (InputStream in = zip.getInputStream(entry)) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); } }
    private static void createOutputParent(Path outputHtml) throws IOException { Path parent = outputHtml.toAbsolutePath().getParent(); if (parent != null) Files.createDirectories(parent); }
    private static String mediaType(String resource) { String type = URLConnection.guessContentTypeFromName(resource); return type == null ? "application/octet-stream" : type; }
    private static ZipEntry requiredEntry(ZipFile zip, String name) throws IOException { ZipEntry entry = zip.getEntry(name); if (entry == null) throw new IOException("Missing EPUB entry: " + name); return entry; }
    private static org.w3c.dom.Document xml(String input) throws IOException { try { DocumentBuilderFactory f = DocumentBuilderFactory.newInstance(); f.setNamespaceAware(true); f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true); f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false); f.setFeature("http://xml.org/sax/features/external-general-entities", false); f.setFeature("http://xml.org/sax/features/external-parameter-entities", false); f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, ""); return f.newDocumentBuilder().parse(new InputSource(new StringReader(input))); } catch (Exception e) { throw new IOException("Invalid EPUB XML", e); } }
    private static String resolve(String base, String reference) { String clean = reference.replace('\\', '/'); try { return normalize(URI.create(base).resolve(clean).getPath() + (URI.create(clean).getFragment() == null ? "" : "#" + URI.create(clean).getFragment())); } catch (Exception e) { int slash = base.lastIndexOf('/'); return normalize((slash < 0 ? "" : base.substring(0, slash + 1)) + clean); } }
    private static String normalize(String path) { String fragment = ""; int hash = path.indexOf('#'); if (hash >= 0) { fragment = path.substring(hash); path = path.substring(0, hash); } return Paths.get(path).normalize().toString().replace('\\', '/') .replaceFirst("^/", "") + fragment; }
    private static String targetFile(String target) { int hash = target.indexOf('#'); return hash < 0 ? target : target.substring(0, hash); }
    private static String targetFragment(String target) { int hash = target.indexOf('#'); return hash < 0 ? null : target.substring(hash + 1); }
    private static boolean isExternal(String value) { return value.startsWith("data:") || value.startsWith("#") || URI.create(value).isAbsolute(); }
    private static String textOf(org.w3c.dom.Element parent, String local) { NodeList items = parent.getElementsByTagNameNS("*", local); return items.getLength() == 0 ? "" : items.item(0).getTextContent().trim(); }
    private static String attributeOf(org.w3c.dom.Element parent, String local, String attribute) { NodeList items = parent.getElementsByTagNameNS("*", local); return items.getLength() == 0 ? null : ((org.w3c.dom.Element) items.item(0)).getAttribute(attribute); }

    private record PackageData(List<String> spine, String ncx, String nav) { }
    private record TocItem(String target, int level, String label) { }
    private record CssRule(String selector, String declarations) {
        static List<CssRule> parse(String css) { ArrayList<CssRule> result = new ArrayList<>(); java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?s)([^{}@]+)\\{([^{}]*)}").matcher(css.replaceAll("(?s)/\\*.*?\\*/", "")); while (m.find()) for (String selector : m.group(1).split(",")) { String s = selector.trim(); if (!s.isEmpty() && !s.startsWith("@")) result.add(new CssRule(s, m.group(2).trim())); } return result; }
    }
}
