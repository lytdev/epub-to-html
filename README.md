# epub2Html4j

基于 JDK 21 的 Maven 解析库：按 EPUB 目录读取正文并返回 List<TocItem> 章节树。每个节点的 content 保存正文 HTML 片段，不再插入目录标题；章节标题独立保存在 label 中，尽力将外部 CSS 转为内联样式，并通过统一接口处理图片、音频、视频等资源。

项目采用“门面入口 → 转换流水线 → 专职组件”的结构，适合嵌入 Spring Boot 等 Java 应用。宿主项目负责文件接收、静态资源路由以及云端上传等业务；本库不包含 HTTP 服务、数据库、OSS SDK 或命令行入口。

库不合并整书 HTML，也不将 HTML 写入本地；最终展示和存储由调用方决定。使用本地或云存储策略时，content 中仍引用外部资源。源码包含中文职责说明、方法参数与返回值说明，以及关键循环、递归和归档读取注释，阅读路线见第 1 节。

## 快速开始

### 构建与依赖

需要 JDK 21，建议使用 Maven 3.9 系列。先确认 Maven 实际运行的 Java 版本：

~~~powershell
mvn -version
mvn clean package
mvn install
~~~

最后一条命令将构件安装到本机 Maven 仓库。团队使用时可发布到团队仓库；以下坐标不表示已发布到 Maven Central。

~~~xml
<dependency>
    <groupId>io.github.agilehub</groupId>
    <artifactId>epub2html4j</artifactId>
    <version>1.0.0</version>
</dependency>
~~~

| 产物 | 使用方式 |
| --- | --- |
| target/epub2html4j-1.0.0.jar | 常规库，使用 Maven 管理 jsoup 传递依赖。 |
| target/epub2html4j-1.0.0-all.jar | 附带运行时依赖的库，适合单 JAR 分发；不是 Spring Boot 可执行应用。 |

Shade 未重定位 jsoup 包名，宿主若同时包含另一版本 jsoup，应检查依赖冲突。

### 公开 API 与迁移说明

提供 Path 和 InputStream 两种输入，各有默认重载和清理选项重载，均返回已填充内容的目录树：

~~~java
List<TocItem> convert(Path epub, EpubResourceHandler resourceHandler) throws IOException;
List<TocItem> convert(InputStream epubInput, EpubResourceHandler resourceHandler) throws IOException;
List<TocItem> convert(Path epub, EpubResourceHandler resourceHandler, boolean removeClasses) throws IOException;
List<TocItem> convert(InputStream epubInput, EpubResourceHandler resourceHandler, boolean removeClasses) throws IOException;
~~~

removeClasses 默认 false，保留正文的 class 和 id。传入 true 时，在章节定位、样式内联、资源处理完成后，同时删除所有正文元素的 class 和 id，再保存 content；保留 style、src 等其他属性，不修改 TocItem.target 和 children。

~~~java
List<TocItem> chapters = new EpubConverter().convert(input, resourceHandler, true);
~~~

注意：尽管选项名为 removeClasses，启用后也会删除 id。正文中的锚点链接、SVG 内部引用、宿主按 class/id 绑定的样式或交互可能因此失效；库不会重写这些引用。选项仅作用于本次调用，不改变转换器后续调用的默认行为。

这是不兼容的 API 调整：删除带 outputHtml / mediaDirectory 参数的旧重载，以及 ConversionResult；输入流重载不再返回 String。调用方需改为接收 List<TocItem>，自行决定展示、合并、接口响应或持久化。库不生成完整 HTML，也不写入 HTML 文件。

### Spring Boot：上传文件解析为章节树

以下代码放在宿主服务方法内；file 为 MultipartFile，resourceHandler 为宿主提供的同步资源策略：

~~~java
import io.github.agilehub.epub2html.EpubConverter;
import io.github.agilehub.epub2html.TocItem;
import java.io.InputStream;
import java.util.List;

try (InputStream input = file.getInputStream()) {
    List<TocItem> chapters = new EpubConverter().convert(input, resourceHandler);
    return chapters; // 宿主自行返回 JSON、展示或保存，不需要先合并 HTML。
}
~~~

输入流从当前位置读取到结束，不要求 mark/reset，库不关闭调用方的流。
内部 TemporaryEpub 将 EPUB 暂存到系统临时目录供 ZipFile 随机读取，在成功或失败时清理；清理失败通过异常报告。因此输入流方案需要可写临时目录，并非完全无磁盘。
返回之前已读取全部章节并关闭归档，之后仍可访问和修改目录树。

### 文件输入与资源策略

~~~java
import io.github.agilehub.epub2html.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Base64;

// 本地媒体存储是调用方显式选择的策略，并不保存章节 HTML。
List<TocItem> chapters = new EpubConverter().convert(
    Path.of("book.epub"),
    new LocalResourceHandler(Path.of("storage/book-assets"), "/assets/book-001"));

// 不保存媒体文件：将资源编码为 data URL，直接写入对应节点的 content。
List<TocItem> embedded = new EpubConverter().convert(
    Path.of("book.epub"),
    resource -> "data:" + resource.mediaType() + ";base64,"
        + Base64.getEncoder().encodeToString(resource.content()));
~~~

宿主需自行将 /assets/book-001 映射到实际存储目录；库不配置 HTTP 路由。
OSS 等存储由宿主实现 EpubResourceHandler.handle：同步消费 archivePath、mediaType、content 并返回非空白 URL。相同归档路径在一次转换中只调用一次，跨转换不共享缓存。
Base64 会增加 content 大小。上传失败不会自动回滚已经保存的资源；幂等、清理和事务补偿由宿主负责。

### 返回值约定

| 字段 / 访问器 | 含义 |
| --- | --- |
| 根 List<TocItem> | 只包含根章节，保留目录顺序；无目录时按 spine 创建无标题节点。 |
| getTarget() | EPUB 内部路径，可含 #锚点；无链接分组为 null，不是外部访问 URL。 |
| getLevel() | 从 1 开始的原始层级；不再据此生成正文标题。 |
| getLabel() | 目录标题。 |
| getContent() | 本节点正文的 HTML 片段（不插入 label 标题），不含 html/body 外壳，也不含子章节正文。 |
| getChildren() | 直接子节点，叶子为空列表。 |

返回列表和节点可修改，调用方不得引入循环引用，跨线程共享需自行同步。setContent(null) 归一为空字符串。
资源数量、最终地址和上传记录如需审计，请在调用方的处理器中记录，不再通过 ConversionResult 获取。

## 1. 项目组织结构

~~~text
src/main/java/io/github/agilehub/epub2html/
├── EpubConverter.java          # Path / InputStream 公开入口
├── TemporaryEpub.java          # 输入流暂存及清理
├── ConversionPipeline.java     # 读取目录 → 填充内容 → 返回树
├── NavigationReader.java       # NCX / EPUB 3 目录树
├── TocItem.java                # target、level、label、content、children
├── TocContentReader.java       # 根据目录目标填充 content
├── ChapterRange.java           # 同页锚点区间裁剪
├── HeadingProcessor.java       # 历史标题工具（不再用于转换流水线）
├── StyleProcessor.java         # CSS 内联
├── MediaProcessor.java         # HTML / SVG 资源处理
├── FigureProcessor.java        # 图片与紧邻图注组合为 figure
├── EpubResourceHandler.java    # 调用方资源策略
├── LocalResourceHandler.java   # 本地存储实现
├── EpubResource.java           # 资源数据
├── PackageReader.java          # container.xml / OPF
├── PackageData.java            # 包信息
├── EpubArchive.java            # 归档条目读取
├── EpubXml.java                # XML 安全解析
└── EpubPaths.java              # 引用路径处理
~~~

HtmlAssembler、TocHtmlWriter 和 ConversionResult 已移除。库只填充并返回 TocItem 内容树；最终输出完全由调用方负责。项目仍是一个 Maven 模块，内部处理器保持包内可见。

| 组件 | 职责与边界 |
| --- | --- |
| EpubConverter | 参数校验、Path / InputStream 输入适配并返回章节树。 |
| ConversionPipeline | 管理归档生命周期，填充内容后关闭归档并返回树。 |
| NavigationReader / PackageReader | 提供目录树和包描述，不读取正文内容。 |
| TocContentReader | 遍历树建立目标读取计划，逐文件处理并填充节点自身内容。 |
| ChapterRange | 根据节点位置裁剪 HTML，保留区间内正文及必要祖先结构。 |
| 样式、标题、媒体处理器 | 复用现有转换规则，不直接负责整书拼接。 |

初学者建议依次阅读 EpubConverter、ConversionPipeline、TocItem、TocContentReader、ChapterRange，再查看各专项处理器。中文 Javadoc 解释参数和结果，行内注释解释去重、区间裁剪及生命周期。

## 2. 目录驱动的技术方案

### 内容模型

~~~text
List<TocItem>
└── 第一章
    ├── target = OPS/a.xhtml
    ├── level = 1
    ├── content = 本章引言
    └── children
        ├── 第一节
        │   ├── target = OPS/a.xhtml#one
        │   ├── content = 第一节正文
        │   └── children = []
        └── 第二节
            ├── target = OPS/a.xhtml#two
            ├── content = 第二节正文
            └── children = []
~~~

getContent() 返回本节点的 HTML 片段（不插入目录标题，不含 html/body 外壳），不包含 children 的正文。setContent(null) 保存为空字符串。getChildren() 返回直接子节点，叶子为空列表。

### 完整处理链路

~~~text
EPUB / 输入流
 → PackageReader + NavigationReader
 → List<TocItem> 根目录树
 → TocContentReader.read
     → 递归访问 target，按归档文件分组
     → 每个 XHTML 只解析一次
     → 在完整 DOM 上内联 CSS
     → ChapterRange 按同文件锚点划分不重叠区间
     → 每个片段处理资源和图注
     → 保存到当前 TocItem.content
 → 关闭 ZipFile
 → 返回 List<TocItem>
 → 调用方决定展示、合并或持久化
~~~

读取计划按目录先序遍历的首次出现顺序建立。同一文件的各片段先在一起读取，返回树保留原目录层级和顺序，文件分组不改变树结构。

### 目录与区间规则

| 情况 | 当前行为 |
| --- | --- |
| 存在目录 | 只读取 target 引用的文件，即使目标不在 spine 中也可读取；未被目录引用的独立文件不会自动追加。 |
| 没有目录 | 使用 spine 创建无标题节点，填充 content 后返回。 |
| target 无片段或片段为空 | 以目标文件 body 开头为起点。 |
| 同文件多个锚点 | 按原 DOM 的锚点先序位置划分区间，当前起点包含、下一起点不包含；最后一个延续到文件末尾。 |
| 父子共用文件 | 父项只保留子项开始前的内容，不重复包含子项整段正文。 |
| 多项指向相同位置 | 标题保留在各项 label 中；正文只分配给该位置最后一个目录项，其余 content 为空。 |
| 无链接分组 | content 为空字符串，标题保留在 label 中，子目录独立读取。 |
| 目标文件或锚点缺失 | 抛出 IOException，避免错误地把整页内容当作该章节。 |
| 第一锚点之前的内容 | 没有文件级目录项覆盖时不纳入 content；严格从目录指定位置开始。 |
| 区间跨越祖先容器 | 重建必要祖先并裁剪子节点；跨片段复制的祖先去除 id，其他属性保留。 |

文本级行内锚点也按节点位置切分，锚点前后的内容可能成为不同片段，必须保留它们各自的必要父结构。目录树不提供“结束锚点”，所以内容区间以同文件下一个目录目标为结束边界。

### 技术选型与限制

图片图注自动组合：完成样式、标题及资源处理后，若 img 紧邻的 p、div 或 span 以“图＋阿拉伯数字/全角数字”开头（例如“图6　《神仙赴会图》东壁后部”），将两者转换为 figure，图片为 img，图注为 figcaption。figcaption 内仅保留文本，不嵌套 p、span 或链接；保留图注容器自身属性，子元素的样式和链接功能不再保留。随后仍遵守 removeClasses 清理选项。

识别只忽略空白和注释，不跨过正文、其他元素或章节边界；支持仅含图片的 p/div 容器后接图注。不重复包装已有 figure，但会将已有 figcaption 的内部内容也转为文本；不自动把章节标题、含块级正文或媒体的复杂相邻容器识别为图注。新建 figure 使用 margin: 0，避免引入浏览器默认外边距；复用单图容器时保留其原属性。

~~~html
<figure style="margin: 0">
  <img src="处理器返回的图片地址">
  <figcaption>图6　《神仙赴会图》东壁后部</figcaption>
</figure>
~~~

| 技术 | 用途 |
| --- | --- |
| JDK 21 ZipFile / JAXP | 随机访问 EPUB 条目，安全读取 OPF/NCX。 |
| Jsoup 1.18.3 | XHTML DOM、节点裁剪、标题转换、属性修改和片段序列化。 |
| 策略接口 | EpubResourceHandler 隔离本地、OSS、Base64 差异。 |
| Maven Shade 3.6.0 | 保留普通 JAR，并生成 all 附加构件。 |
| JUnit 5.11.4 | 验证目录结构、内容归属、顺序和资源策略。 |

原有能力与限制继续适用：

- CSS 采用简化规则：外部声明按源码顺序收集，原始内联声明最后追加，不再将已收集的外部规则当成原始内联样式。保留简写/长写、重复声明及 !important，由浏览器解释声明覆盖；仍未实现完整选择器 specificity 或媒体条件，不收集 head 内 style。
- 媒体支持普通 src/data 及 SVG image 的 href/xlink:href；不覆盖 poster、srcset、CSS url、SVG use。
- 所有片段共用一次转换的资源缓存，同一归档资源只处理一次。
- 章节 content 不增加额外包装，不新增 section.epub-chapter 包装；原正文容器保留。
- 目录标题仅保存在 label 中，不生成或改名正文标题；源 XHTML 原有标题保留。
- 不重写跨章节超链接，不执行安全清洗，也不保证复杂排版与原阅读器完全一致。
- 完整内容树仍占用内存，但库不再额外构造整书字符串，媒体仍完整读取为 byte[]。
- 资源可能在返回树之前已保存或上传；异常时不自动回滚外部资源。

## 3. 核心调用关系

### 文件或输入流转换

~~~text
EpubConverter.convert
 → 输入流时先经 TemporaryEpub
 → ConversionPipeline.convert
     → NavigationReader.readToc
     → TocContentReader.read
         → StyleProcessor.inlineStyles
         → ChapterRange.copy
         → MediaProcessor.rewriteMedia
             → EpubResourceHandler.handle
         → TocItem.setContent
 → 关闭 ZipFile 并返回 List<TocItem>
 → 调用方独立处理结果
~~~

### 同页父子章节

~~~text
父章 target=a.xhtml
子节 target=a.xhtml#part
 → 同一文件读取一次
 → 父 content 保存 [body 开始, part)
 → 子 content 保存 [part, 下一锚点或文件结束)
 → 返回父节点及 children，父子 content 独立保存
~~~

### 不同文件与目录顺序

当 spine 为 A、B，而目录为 B、A 时，返回根列表顺序为 B、A。目录引用 C、但 C 不在 spine 中时，仍读取 C。此变化是本次改造的核心：spine 不再驱动有目录书籍的读取顺序。

## 4. 设计意图与维护

- **解析与消费分离**：库返回内容树，宿主负责消费。相比固定输出 HTML，适合章节入库、目录展示和 API 返回，代价是宿主需要自行实现最终渲染。
- **内容归属明确**：父节点不保存子正文，先序输出不会重复；代价是必须处理同文件锚点区间。
- **完整 DOM 先处理样式**：保留原文件结构供 CSS 匹配，再裁剪各章，减少因拆分改变选择器上下文的影响。
- **存储仍使用策略模式**：本次更换内容组织方式，不要求宿主更改资源处理器。
- **精简公开契约**：两个入口统一返回 List<TocItem>，不保留隐含写文件行为的兼容重载；旧调用方必须迁移。
- **单一职责与策略模式**：TocContentReader 仅填充正文，输出逻辑不属于库；资源策略继续支持本地或远程存储，无需引入 Spring 依赖。同步调用便于明确完成与失败边界，相比异步回调更简单，但耗时上传会阻塞解析。

| 修改目标 | 优先查看 |
| --- | --- |
| 目录树结构 | NavigationReader / TocItem |
| 内容归属、读取策略 | TocContentReader |
| 锚点边界、祖先裁剪 | ChapterRange |
| 最终展示、合并、持久化 | 宿主项目，不属于解析库 |
| 新增存储方案 | 宿主实现 EpubResourceHandler |
| 样式或媒体兼容性 | 对应 Processor |

后续可增加读取选项以配置无目录回退、未覆盖前言、重复目标和失效链接的处理策略；也可按实际书籍数据完善表格、列表等复杂容器的片段裁剪测试。

**待补充**：不同来源 EPUB 的锚点质量、实际并发与内存预算、云存储幂等和清理策略。历史技术选型的性能评估依据仍需项目维护者补充。

## 5. 回归验证

~~~powershell
mvn test
mvn package
~~~

当前 37 个测试覆盖：

| 测试类 | 核心覆盖 |
| --- | --- |
| EpubConverterTest | 现有真实 EPUB 的本地与 Base64 转换。 |
| ConversionPipelineTest | 目录目标读取、输入流与路径返回树一致、无 HTML 文件输出、失败传播及临时文件清理。 |
| HeadingProcessorTest | 标题 h1～h6、锚点、原有标题和属性保留。 |
| NavigationReaderTest | NCX / EPUB 3 父子树及分组节点。 |
| SvgResourceTest | SVG 封面 href/xlink:href 和资源去重。 |
| FigureProcessorTest | 紧邻图注、单图段落、图注纯文本与属性保留、文本转义、非图注排除及重复调用。 |
| StyleProcessorTest | demo.epub 的全局重置与段落下边距顺序、原始内联样式、简写/长写及重复声明保留；拼接时不额外添加双分号，保留属性值内分号。 |
| EncodedResourceTest | 带空格章节路径、编码中文图片名、字面百分号与加号，以及项目 demo.epub 图片回写。 |
| TocContentReaderTest | content 实际归属、目录覆盖 spine、父子不重复、同目标只读取一次、失效锚点失败。 |

测试报告位于 target/surefire-reports。现有真实样例测试包含 E: 盘上的本机输入路径，换环境时需提供对应 EPUB；本地媒体写入测试临时目录，测试不写出 HTML；其他用例使用构造归档和临时目录。

### 标题与正文分离

转换不再将 TocItem.label 拼接到 content。content 只保存目标区间的正文，原 XHTML 中已有的标题不删除、不按目录层级改名。无链接分组仅保留 label 和 children，content 为空。调用方如需展示目录标题，应单独读取 label，避免假定 content 开头一定存在 h1～h6。
