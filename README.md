# epub2Html4j

基于 JDK 21 的 Maven 转换库：按 EPUB 的阅读顺序合并正文为一个 HTML 文件，根据目录插入 h1～h6 标题，尽力将外部 CSS 转为内联样式，并通过统一接口处理图片、音频、视频等资源。

项目采用“门面入口 → 转换流水线 → 专职组件”的结构，适合嵌入 Spring Boot 等 Java 应用。宿主项目负责文件接收、静态资源路由以及云端上传等业务；本库不包含 HTTP 服务、数据库、OSS SDK 或命令行入口。

“单个 HTML”指正文合并：使用本地或云存储策略时，页面仍需访问外部资源。源码包含中文职责说明、方法参数与返回值说明，以及关键循环、递归和文件读写注释，阅读路线见第 1 节。

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

### 本地资源

~~~java
import io.github.agilehub.epub2html.ConversionResult;
import io.github.agilehub.epub2html.EpubConverter;
import java.nio.file.Path;

Path epub = Path.of("book.epub");
Path html = Path.of("output/book.html");
Path media = Path.of("output/media");
ConversionResult result = new EpubConverter().convert(epub, html, media);
~~~

便捷重载使用资源目录的最后一级名称作为 URL 前缀，生成类似 media/OPS/image/a.png 的引用，因此通常要求 HTML 与资源目录同级。实际磁盘目录和访问地址不同的场景，可显式指定：

~~~java
import io.github.agilehub.epub2html.LocalResourceHandler;

new EpubConverter().convert(epub, html,
    new LocalResourceHandler(Path.of("storage/book-assets"), "/assets/book-001"));
~~~

宿主需自行把 /assets/book-001 映射到实际存储目录，库不会配置 HTTP 路由。

### 自定义策略与 Base64

实现 EpubResourceHandler.handle 即可接入 OSS 等服务。输入包含 archivePath、mediaType 和 content；方法应同步完成处理并返回非空白 URL。以下是接口接入示意，上传实现由业务项目提供：

~~~java
import io.github.agilehub.epub2html.EpubResourceHandler;

EpubResourceHandler handler = resource -> {
    // 在这里完成真实上传：内容为 resource.content()。
    // objectKey 建议包含书籍标识，避免不同书的同名资源覆盖。
    // MIME 可取 resource.mediaType()；最终返回实际可访问的地址。
    return "https://cdn.example.com/book-001/" + resource.archivePath();
};
new EpubConverter().convert(epub, html, handler);
~~~

上述占位代码只返回地址，本身不会上传。Base64 方式则可直接使用：

~~~java
import java.util.Base64;

new EpubConverter().convert(epub, html, resource ->
    "data:" + resource.mediaType() + ";base64," +
        Base64.getEncoder().encodeToString(resource.content()));
~~~

Base64 会增大 HTML。转换器不会等待额外的异步任务，仅提交上传任务便返回地址不能保证资源已可用。content() 返回原数组，不做防御性复制；异步代码保留资源时应自行管理生命周期与并发访问。

### 结果含义

| 访问器 | 含义 |
| --- | --- |
| htmlFile() | 已写出的 HTML 文件路径。 |
| mediaDirectory() | 本地目录重载传入的目录；处理器重载为 null，即使使用 LocalResourceHandler。 |
| copiedMedia() | 成功处理的归档路径，按首次处理顺序去重；不是最终 URL，也不意味着一定复制到本地。 |
| tocEntries() | 解析到的目录项数；缺失锚点可能导致对应标题未插入。 |

## 1. 项目组织结构与阅读指引

### 目录树

~~~text
epub2Html4j/
├── pom.xml
├── README.md
├── demo.epub
├── src/main/java/io/github/agilehub/epub2html/
│   ├── EpubConverter.java          # 公开门面
│   ├── ConversionPipeline.java     # 单次转换编排
│   ├── PackageReader.java          # container.xml / OPF 解析
│   ├── NavigationReader.java       # NCX / EPUB 3 导航
│   ├── StyleProcessor.java         # CSS 收集与内联
│   ├── HeadingProcessor.java       # 插入目录标题
│   ├── MediaProcessor.java         # 资源处理与地址回写
│   ├── HtmlAssembler.java          # 章节合并与输出
│   ├── EpubArchive.java            # ZIP 条目读取
│   ├── EpubXml.java                # 安全 XML 解析
│   ├── EpubPaths.java              # 归档引用解析
│   ├── PackageData.java            # 内部包信息
│   ├── TocItem.java                # 内部目录项
│   ├── EpubResourceHandler.java    # 公开资源策略接口
│   ├── LocalResourceHandler.java   # 默认本地实现
│   ├── EpubResource.java           # 公开资源数据
│   └── ConversionResult.java       # 公开转换结果
├── src/test/java/io/github/agilehub/epub2html/
│   ├── EpubConverterTest.java
│   └── ConversionPipelineTest.java
└── target/                         # 构建产物与测试报告
~~~

这是一个 Maven 模块、一个 Java 包。分层指职责分层；除门面、资源接口、本地实现及两个公开数据对象外，其余组件均为 package-private，供包内协作。

### 职责边界

| 层次 | 组件 | 职责与边界 |
| --- | --- | --- |
| 门面 | EpubConverter | 校验输入、准备输出父目录、适配本地重载；不实现解析算法。 |
| 编排 | ConversionPipeline | 管理归档生命周期、目录索引和资源缓存，按顺序调用各阶段。 |
| 读取 | PackageReader、NavigationReader | 分别得到阅读顺序与导航层级，不操作媒体存储。 |
| 章节处理 | StyleProcessor、HeadingProcessor、MediaProcessor | 分别原地修改样式、标题和媒体引用；不共享跨转换状态。 |
| 输出 | HtmlAssembler | 持有本次转换的结果 DOM，移动章节节点并写出 HTML。 |
| 基础工具 | EpubArchive、EpubXml、EpubPaths | 封装条目读取、XML 安全设置及路径解析。 |
| 策略与数据 | EpubResourceHandler、LocalResourceHandler、各 record | 隔离存储差异，传递资源、目录、包信息与输出摘要。 |

### 初学者阅读顺序

1. 从 EpubConverter.convert 开始，区分两个重载及本地策略的适配过程。
2. 阅读 ConversionPipeline.convert 和 processChapter，先了解完整流程。
3. 阅读 PackageReader 和 PackageData，理解 manifest 是文件索引、spine 是阅读顺序。
4. 阅读 NavigationReader、TocItem、HeadingProcessor，跟踪目录树如何递归展开，再定位到正文。
5. 阅读 StyleProcessor、MediaProcessor，理解 DOM 修改与回调去重。
6. 阅读 HtmlAssembler；遇到归档、路径和 XML 细节，再查询三个基础工具类。

中文类注释解释职责和上下游，方法 Javadoc 说明参数、结果、副作用和异常，内部注释解释关键分支与资源关闭责任。void 方法可能直接修改传入的文档或集合，不一定需要返回新对象。record 自动生成同名访问器，但其中的数组、集合不会因此自动深度不可变。

## 2. 核心技术方案

### 业务问题与选型

EPUB 将正文、样式和媒体放在 ZIP 中，正文可能分散于多个 XHTML 文件。需要分别解决阅读顺序、导航层级、相对引用和输出文件组织，才能生成可供宿主展示的 HTML。

| 技术或模式 | 当前用途 |
| --- | --- |
| JDK 21 ZipFile | 打开 EPUB 并读取条目，由 try-with-resources 关闭。 |
| JAXP DOM | 读取容器、OPF、NCX，统一禁用外部 DTD/实体读取。 |
| Jsoup 1.18.3 | XML 模式读取 XHTML，选择和修改节点，HTML 模式序列化结果。不是浏览器布局引擎。 |
| 门面模式 | EpubConverter 保持公开 API 稳定，隐藏内部拆分。 |
| 顺序流水线 | ConversionPipeline 组合各阶段，集中控制顺序与状态。 |
| 策略模式 | EpubResourceHandler 支持宿主选择本地、对象存储或编码实现。 |
| Shade 3.6.0 | 生成附带 jsoup 的 all 构件，同时保留常规 JAR。 |
| JUnit 5.11.4 / Surefire 3.5.2 | 执行真实样例和小型多章节回归测试。 |

### 数据流

~~~text
EPUB Path
  → ZipFile
  → PackageReader → PackageData(spine, ncx, nav)
  → NavigationReader → List<TocItem> → 按目标分组
  → 按 spine 逐章读取 Jsoup Document
      → StyleProcessor
      → HeadingProcessor
      → MediaProcessor → EpubResourceHandler → 最终 URL
      → HtmlAssembler.append
  → HtmlAssembler.write → HTML 文件
  → ConversionResult
~~~

各阶段的实际行为：

1. **书籍结构**：从固定入口 META-INF/container.xml 取第一个 rootfile，定位 OPF。manifest 的 ID 映射为路径，再按 spine 的 idref 顺序得到章节。无法映射的 idref 被跳过，空 spine 报错。
2. **目录解析**：优先使用存在的 NCX，否则读取 OPF 标记的 EPUB 3 导航文件；不要求文件名为 nav.xhtml。选中 NCX 后解析失败直接报错，不回退。无可用导航时返回空目录。
3. **样式处理**：收集 link 引用的 CSS，递归读取导入文件，然后用简化规则匹配原章节 DOM。该步骤先于新增标题。
4. **标题处理**：按目标文件和 #fragment 定位锚点。无片段时插入章节开头；有片段时插入对应元素之前；缺失锚点则跳过。深度超过 6 时使用 h6，并保存原始层级。不会删除原标题或按目录重排正文。
5. **媒体处理**：对支持标签优先选 src，没有 src 时选 data。查到资源后完整读为 byte[]，同步调用策略，缓存非空白返回值并回写属性。
6. **正文合并**：把章节 body 子节点移动到 section.epub-chapter 中。所有章节完成后直接写出目标 HTML。

资源缓存位于章节循环之外、单次调用之内：同一本书跨章节复用 URL，新转换会重新处理。HtmlAssembler 同样每次创建，不能跨转换共享。宿主若并发复用处理器，需保证其线程安全并隔离输出路径。

### 当前限制与失败行为

| 范围 | 当前实现与边界 |
| --- | --- |
| CSS 优先级 | 已有 style 覆盖当前规则；先前规则也已经写入 style，可能压过后续规则。不实现完整 specificity、!important 或媒体条件。 |
| CSS 来源 | 仅收集链接样式，未收集 head 内 style；目前删除所有带 href 的 link。正则及分号/逗号拆分不适合复杂 CSS。 |
| EPUB 3 目录 | 取首个匹配 nav；存在多个 nav 时不保证优先选择 toc。 |
| 媒体覆盖范围 | 支持 img/audio/video/source/track/object/embed；不处理 poster、srcset、CSS url 或 SVG 内部引用。 |
| 外部引用 | data:、纯片段和带 scheme 的 URI 跳过读取；//host/path 当前不被识别为外部地址。 |
| 文档合并 | 不复制章节 head 或 body 自身属性；未处理跨章节链接和全书重复 id。 |
| 编码与类型 | 文本按 UTF-8 解码；MIME 来自文件名推断，不读取 OPF 的媒体类型或检查字节。 |
| 内存 | 最终 DOM 累积整本书，每个媒体完整读入内存；没有大小上限或流式处理接口。 |
| 异常 | 读取/XML/策略失败可抛 IOException；非法 URI、空参数等也可能抛运行时异常。可选 CSS/媒体缺失会跳过。 |
| 输出一致性 | 直接覆盖 HTML，没有原子替换；失败可能留下部分文件。已落盘或上传的资源不自动回滚。 |

XML 安全解析不等于 HTML 安全清洗；当前转换器不负责过滤正文脚本或实施展示侧 CSP。资源路径归一化也不等于完整的存储隔离策略，本地处理器另做目标路径越界检查。

## 3. 核心调用关系

### 依赖关系

~~~text
业务项目
  → EpubConverter
      → ConversionPipeline
          ├── PackageReader / NavigationReader
          ├── StyleProcessor
          ├── HeadingProcessor
          ├── MediaProcessor
          │    → EpubResourceHandler.handle(EpubResource)
          │        ├── LocalResourceHandler
          │        └── 业务 OSS / Base64 策略
          └── HtmlAssembler

读取与处理组件按需使用 EpubArchive / EpubXml / EpubPaths
~~~

三个处理器不直接相互调用；顺序由 processChapter 控制，数据通过同一个可变 Document 传递。这是固定步骤组合，不是动态注册的责任链框架。

### 三条代表性路径

**本地输出**

~~~text
convert(epub, html, mediaDirectory)
  → 创建 LocalResourceHandler
  → convert(epub, html, handler)
  → ConversionPipeline.convert
  → MediaProcessor.rewriteMedia
  → handler.handle → Files.write(资源路径) → 返回 URL → 回写 DOM
  → HtmlAssembler.append / write → Files.writeString(HTML)
  → 门面在结果中补回 mediaDirectory
~~~

资源路径保留归档层级，已有文件会被覆盖。URL 前缀与服务器磁盘路径是两个不同概念。

**云端资源**

~~~text
convert(epub, html, ossHandler)
  → ConversionPipeline
  → MediaProcessor：检查路径缓存
  → 未命中时读取字节 → ossHandler.handle → 业务上传 → 返回实际 URL
  → 缓存 URL 并修改章节属性
  → 合并所有章节 → 写本地 HTML → 返回结果
~~~

云存储只负责媒体，HTML 仍写到 outputHtml 本地路径。异常终止当前转换；重试、对象 key 命名和失败清理由宿主管理。

**目录转标题**

~~~text
NavigationReader.readToc
  → readNcx / readNav
  → ncxItems / navItems 递归：父项先入列表，子项 level + 1
  → TocItem(target, level, label)
  → ConversionPipeline.indexTargets
  → HeadingProcessor.injectTocHeadings：筛本章、找锚点、插标题
  → MediaProcessor → HtmlAssembler.append / write → 最终 HTML
~~~

目录顺序与正文顺序承担不同职责：目录用于定位和确定标题层级，正文仍按 spine 合并。

## 4. 设计原则、取舍与演进

### 架构决策

本次拆分明确采用单一职责、组合优于继承和最小公开面原则。历史技术选型的理由属于根据源码的合理推断；原始评审记录、性能对比数据待补充。

| 决策 | 理由与取舍 |
| --- | --- |
| 门面与算法分离 | 保持调用 API 稳定，新增内部组件不增加宿主理解成本。 |
| 包内组件而非新增多个模块 | 当前规模适合职责拆分；package-private 避免内部方法成为公共兼容性负担。 |
| 固定步骤组合而非多层基类 | 顺序可直接阅读；目前没有动态扩展步骤的需求，不为每一步增加接口。 |
| 存储通过接口 | 对外只依赖资源到 URL 的契约，避免绑定厂商 SDK 和凭证模型。 |
| 同步串行而非异步上传 | 顺序、资源关闭和失败传播较清晰；代价是网络等待直接影响转换耗时。 |
| 当前文件输出而非事务 | 文件和云端对象没有统一事务；代价是宿主需要补偿与幂等策略。 |
| 基础 DOM 处理而非浏览器渲染 | 部署依赖较少、便于节点修改；代价是无法保证复杂版式一致。 |

MVC 适合宿主 Web 服务，不适合直接套用到这个转换库。项目有资源策略端口，但尚不是完整六边形架构：输入 EPUB 和输出 HTML 仍直接使用本地文件 API。若未来需支持流输入、存储端口或任务调度，可按实际需求继续拆分。

### 维护定位

| 需求 | 优先修改位置 |
| --- | --- |
| 新增 OSS/其他存储 | 在业务项目实现 EpubResourceHandler。 |
| 修改 OPF/阅读顺序兼容性 | PackageReader。 |
| 修改 NCX/EPUB 3 导航兼容性 | NavigationReader。 |
| 调整标题生成 | HeadingProcessor。 |
| 改进 CSS 解析/层叠 | StyleProcessor。 |
| 支持新的媒体属性 | MediaProcessor。 |
| 修改输出容器与序列化 | HtmlAssembler。 |
| 新增步骤或改变执行顺序 | ConversionPipeline。 |

避免重新把算法放回门面；同时更新中文注释、行为限制和相应回归测试。

### 后续方向与待补充项

- 引入有明确关闭责任的流式资源接口，减少大媒体内存占用。
- 根据需求引入转换选项、警告列表、进度通知和取消机制。
- 补齐 EPUB 3 多 nav、复杂 CSS、异常 XML、路径和大媒体测试。
- 如需更高排版还原度，评估 CSS 解析器或受控保留样式表的方案。
- **待补充**：实际 EPUB 大小、并发量、超时预算及性能数据。
- **待补充**：OSS 命名、访问权限、签名 URL 生命周期、重试与清理策略。
- **待补充**：展示端安全清洗、跨域和字体需求。

## 5. 测试与注释维护

~~~powershell
mvn test
mvn package
~~~

目前测试源码包含 5 个用例：

| 测试类 | 覆盖内容 |
| --- | --- |
| EpubConverterTest | 真实 demo.epub 的本地转换、Base64 回写。 |
| ConversionPipelineTest | 小型多章节 EPUB 的阅读顺序、标题层级、样式、跨章节资源去重与跨调用缓存隔离；处理器抛错传播；空白 URL 拒绝。 |

测试使用 JUnit 临时目录保存输出，报告位于 target/surefire-reports。已有用例不代表复杂 CSS 或所有 EPUB 3 导航结构均已验证。

阅读代码时先看类职责，再看方法 Javadoc，最后按内部注释跟踪分支。维护注释时应核对实际方法声明、参数、返回结果、原地修改和异常；调整逻辑后，不能只修改注释而遗漏技术方案或测试。
