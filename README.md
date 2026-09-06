# epub2Html4j

`epub2Html4j` 是一个基于 JDK 21 的 Maven 库，用于把 EPUB 转换为单个 HTML 文件。它按 EPUB 的阅读顺序合并正文，根据目录生成 `h1`～`h6` 标题，尽力将外部 CSS 转为元素内联样式，并通过统一资源接口处理图片、音频、视频等二进制资源。

适用场景包括：Spring Boot 中的电子书预览、内容审核、全文检索前处理，以及需要将 EPUB 发布到静态站点或对象存储的系统。

> 当前版本为库而非 Web 服务：不包含 Controller、数据库、任务队列或 OSS SDK。宿主项目负责接收文件、鉴权、持久化和资源上传。

## 快速开始

### 环境与构建

- JDK：21
- 构建工具：Maven 3.9+

```powershell
mvn clean package
```

| 文件 | 用途 |
| --- | --- |
| `target/epub2html4j-1.0.0.jar` | 常规 Maven 构件；使用时应让 Maven 一并解析 `jsoup` 依赖。 |
| `target/epub2html4j-1.0.0-all.jar` | 包含运行时依赖的自包含 JAR，适合直接复制给其他 Spring Boot 项目。 |

### 本地文件模式

```java
import io.github.agilehub.epub2html.ConversionResult;
import io.github.agilehub.epub2html.EpubConverter;
import java.nio.file.Path;

Path epub = Path.of("book.epub");
Path html = Path.of("output/book.html");
Path media = Path.of("output/media");

ConversionResult result = new EpubConverter().convert(epub, html, media);
```

该重载会使用 `LocalResourceHandler` 将资源写入 `media` 目录，HTML 中的资源地址形如 `media/OEBPS/image/cover.png`。输出 HTML 与资源目录应由宿主项目作为静态资源一同提供。

### 自定义资源模式：对象存储或 Base64

资源处理器在每个不同的 EPUB 资源首次出现时被同步调用一次，返回值会回写至对应元素的 `src` 或 `data` 属性。

```java
import io.github.agilehub.epub2html.EpubConverter;

EpubConverter converter = new EpubConverter();
converter.convert(epub, html, resource -> {
    // 使用业务项目已有的阿里云 OSS 客户端上传 resource.content()
    // objectKey 可由 resource.archivePath() 生成；上传时使用 resource.mediaType()
    return "https://cdn.example.com/ebooks/" + resource.archivePath();
});
```

```java
import java.util.Base64;

converter.convert(epub, html, resource ->
    "data:" + resource.mediaType() + ";base64," +
        Base64.getEncoder().encodeToString(resource.content()));
```

Base64 适合小图标或离线 HTML；它会显著放大 HTML，视频和大量图片更适合对象存储。处理器必须在 `handle` 调用期间完成字节消费；如需异步上传，应先由调用方复制资源内容或使用其自身的可靠队列机制。

---

## 1. 项目组织结构

### 目录树

```text
epub2Html4j/
├── demo.epub                                  # 集成测试使用的 EPUB 样例
├── pom.xml                                    # JDK 21、依赖与打包配置
├── README.md                                  # 项目说明与维护指南
├── src/
│   ├── main/
│   │   └── java/io/github/agilehub/epub2html/
│   │       ├── EpubConverter.java             # EPUB→HTML 转换编排入口
│   │       ├── EpubResourceHandler.java       # 媒体资源处理扩展点
│   │       ├── LocalResourceHandler.java      # 本地文件资源处理实现
│   │       ├── EpubResource.java              # 传递给处理器的资源值对象
│   │       └── ConversionResult.java          # 转换结果值对象
│   └── test/
│       └── java/io/github/agilehub/epub2html/
│           └── EpubConverterTest.java         # 本地与 Base64 端到端测试
└── target/                                    # Maven 构建产物（生成目录，不提交）
```

### 模块职责与边界

| 区域 | 核心职责 | 明确边界 |
| --- | --- | --- |
| `EpubConverter` | 读取 ZIP/EPUB 元数据、解析目录和章节、内联样式、组装 HTML、触发资源回调。 | 不负责 HTTP、数据库、OSS SDK、持久化任务或前端展示。 |
| `EpubResourceHandler` | 定义“资源字节 → HTML 可用 URL”的策略契约。 | 不理解 OPF、目录、章节或 HTML 组装逻辑。 |
| `LocalResourceHandler` | 将资源安全写入指定目录并生成相对 URL。 | 仅是默认策略；不负责云端上传或访问权限。 |
| `EpubResource` / `ConversionResult` | 在转换器、资源策略和宿主应用之间传递输入输出事实。 | 不承载业务状态或流程控制。 |
| `src/test` | 用真实 EPUB 验证标题、样式、资源复制与 Base64 回写。 | 不替代面向不同 EPUB 版本的完整兼容性测试矩阵。 |

### 为什么按此方式组织

当前代码规模较小，采用“转换核心 + 端口接口 + 默认适配器 + 值对象”的轻量分层，而非完整 MVC 或多模块工程：

- **隔离关注点**：EPUB 解析与资源落地的变化频率不同。解析规则集中在 `EpubConverter`，存储方式通过 `EpubResourceHandler` 替换。
- **降低宿主耦合**：库不依赖 Spring、阿里云 SDK 或特定文件系统，因此可嵌入任意 Java/Spring Boot 项目。
- **便于扩展**：新增 S3、OSS、MinIO、加密 URL 等策略只需实现一个接口，无需改动转换主链路。
- **保持可维护性**：内部 OPF/NCX/CSS 细节未暴露为公共 API，减少后续兼容性演进的约束。

---

## 2. 核心实现原理

### 要解决的问题

EPUB 本质上是 ZIP 包：正文通常分散在多个 XHTML 文件中，阅读顺序由 OPF 的 `spine` 定义；目录由 NCX 或 EPUB 3 的 `nav.xhtml` 定义；CSS 和媒体资源又使用相对路径引用。直接解压后无法得到一个可独立投放的 HTML 文档。

本项目解决的关键点是：在不丢失正文顺序的前提下合并章节、将导航层级显式映射为 HTML 标题、消除外部 CSS 依赖，并把资源 URL 交给宿主系统的存储策略。

### 技术选型

| 技术/模式 | 使用位置 | 选择理由与适用场景 |
| --- | --- | --- |
| JDK `ZipFile` | 打开 EPUB 和读取归档条目 | EPUB 是标准 ZIP 容器；无需额外 EPUB 框架即可控制路径解析与资源读取。 |
| JAXP DOM | 读取 `container.xml`、OPF、NCX | 这些文件是 XML，DOM 足以处理当前所需的包描述和导航层级。解析时关闭外部 DTD/实体访问，避免读取外部资源。 |
| Jsoup | 解析 XHTML、选择 CSS 元素、输出 HTML | 便于移动章节节点、定位锚点、回写属性并生成浏览器友好的 HTML。 |
| 策略模式 | `EpubResourceHandler` | 将“资源处理”从“文档转换”中解耦，适用于同一 EPUB 需写本地、上传云端或内嵌数据的场景。 |
| Maven Shade Plugin | `-all.jar` | 将 `jsoup` 打入可分发 JAR，适合未建立私服依赖管理的接入项目。 |
| JUnit 5 | 端到端测试 | 验证真实 EPUB 走过转换链路，而不只验证孤立字符串处理。 |

### 主处理链路与数据流

```text
输入 Path(epub)
    │
    ▼
ZipFile
    │  META-INF/container.xml
    ▼
OPF 包描述 ──► manifest / spine / ncx 或 nav
    │                         │
    │                         └──► 目录项(target, level, label)
    ▼
按 spine 顺序读取 XHTML 章节
    │
    ├──► 读取 link 样式表与 @import → CSS 选择器匹配 → 内联 style
    ├──► 目录 target 定位锚点 → 插入 h1～h6
    ├──► 媒体 src/data → EpubResourceHandler → 新 URL 回写
    └──► 章节 body 子节点 → 单一 HTML 的 section.epub-chapter
    │
    ▼
输出 Path(outputHtml) + ConversionResult
```

处理细节：

1. `container.xml` 指出 OPF 的位置；`readPackage` 从 OPF 中取得清单、spine 和导航文件。
2. 优先解析 NCX；没有 NCX 时解析 EPUB 3 导航文档。目录层级限制在 HTML 支持的 `h1`～`h6`。
3. 对每个 spine 章节，读取其声明的 CSS（包含递归 `@import`），尝试用 Jsoup 选择器匹配元素，并把声明合并到已有内联样式；原有内联样式优先。
4. 对 `img`、`audio`、`video`、`source`、`track`、`object`、`embed` 的 `src` 或 `data` 引用，读取 ZIP 条目并交给资源处理器。相同归档路径会缓存返回 URL，避免重复处理。
5. 章节正文被包裹为 `section.epub-chapter`，依 spine 顺序追加到最终 HTML 的 `body`。

### 当前行为与约束

| 范围 | 当前行为 | 接入或维护注意事项 |
| --- | --- | --- |
| CSS | 支持普通规则、逗号分隔选择器和 `@import`；不可识别选择器会跳过，不中断整书转换。 | 不是完整浏览器 CSS 引擎；复杂嵌套规则、部分伪类和 `@media` 的最终渲染效果需按业务 EPUB 验证。 |
| 媒体 | 处理指定标签的 `src` 或 `data`。 | 不会处理 CSS 中 `url(...)`、`srcset` 或 SVG 内部引用；若有此需求需扩展转换器。 |
| 内存 | 资源会以 `byte[]` 传递给同步处理器。 | 超大视频可能造成内存压力；后续可改为流式资源接口。 |
| 事务 | 单次转换发生异常即抛出 `IOException`，已写出的外部资源不会自动回滚。 | 上传 OSS 时建议宿主应用记录对象 key，并在失败后执行清理或采用临时前缀。 |

---

## 3. 关键类/方法调用关系

### 类依赖关系

```text
业务项目 / Spring Bean
        │
        ▼
EpubConverter
 ├── ZipFile + JAXP DOM        读取容器、OPF、NCX
 ├── Jsoup                     解析章节、应用样式、输出 HTML
 ├── EpubResourceHandler ──────┬── LocalResourceHandler
 │                             ├── OSS/MinIO/S3 实现（业务项目提供）
 │                             └── Base64 Lambda（业务项目提供）
 └── ConversionResult          返回 HTML、资源路径清单、目录统计

EpubResource ─────────────────► EpubResourceHandler.handle(...)
```

### 代表性功能调用路径

#### 1）本地文件转换

```text
EpubConverter.convert(epub, html, mediaDirectory)
  └── new LocalResourceHandler(mediaDirectory, mediaDirectory.getFileName())
      └── convert(epub, html, resourceHandler)
          ├── packagePath → readPackage → readToc
          ├── 对每个 spine 章节执行 inlineStyles / injectTocHeadings / rewriteMedia
          └── Files.writeString(html)

rewriteMedia
  └── LocalResourceHandler.handle(resource)
      └── Files.write(mediaDirectory/OEBPS/..., resource.content())
          └── 返回 media/OEBPS/... 并回写 HTML
```

#### 2）阿里云 OSS 等对象存储转换

```text
EpubConverter.convert(epub, html, ossHandler)
  └── rewriteMedia(..., ossHandler, resourceUrls)
      ├── 首次遇到归档路径：ZipFile InputStream → byte[] → EpubResource
      ├── ossHandler.handle(resource)
      │   └── 业务项目上传至 OSS，返回 CDN/签名/公开 URL
      └── resourceUrls 缓存 URL；同一路径的后续元素直接复用
```

库不直接调用阿里云 SDK：凭证、bucket、访问控制、重试策略属于宿主应用的基础设施边界。

#### 3）目录和标题生成

```text
readToc
  ├── readNcx → ncxItems（递归保留 navPoint 层级）
  └── readNav → navItems（递归保留 ol/li 层级）
      └── List<TocItem(target, level, label)>

injectTocHeadings(chapter, chapterPath, toc)
  ├── 过滤 target 指向当前 spine 章节的目录项
  ├── target 有 #fragment：在对应 id 元素前插入标题
  └── target 无片段：在章节 body 开头插入标题
```

### 调用链设计取舍

| 取舍 | 当前设计 | 原因 |
| --- | --- | --- |
| 解析与存储 | 同步串行解析，资源存储经同步回调完成 | 保持章节顺序、异常传播和输出一致性简单；调用方可控制 OSS 重试。 |
| 扩展方式 | 接口回调，而不是内置多家云 SDK | 避免库携带云厂商依赖、凭证模型和版本约束。 |
| 一致性 | 不提供跨 HTML 文件与外部资源的事务 | 本地文件与对象存储没有统一事务协议；由宿主应用按业务补偿。 |
| 并发边界 | `EpubConverter` 无状态，可被多线程复用；单次 `convert` 内部按章节串行 | 避免同一本书中标题和资源 URL 的顺序、缓存发生竞态。 |

---

## 4. 设计意图与架构决策说明

### 已由源码体现的决策

| 决策 | 为什么这样做 | 可选方案与未采用原因 |
| --- | --- | --- |
| 使用 OPF `spine` 决定正文顺序 | EPUB 的文件名顺序不代表阅读顺序，spine 才是规范语义。 | 按 ZIP 条目枚举更简单，但会打乱多章节书籍内容。 |
| 导航优先 NCX、回退 `nav.xhtml` | 同时覆盖传统 EPUB 2 与 EPUB 3 的常见目录格式。 | 只支持一种格式会缩小兼容范围。 |
| 转换为单一 HTML | 便于浏览、审核和存档，降低部署时章节跳转与相对路径管理成本。 | 保留多 HTML 文件更接近原 EPUB，但宿主需维护跨文件链接和资源基准路径。 |
| CSS 内联 | 生成结果尽量独立，方便投放到富文本/静态页环境。 | 继续引用 CSS 文件体积更小，但需要额外资源发布和路径管理。 |
| 资源策略端口 | 云存储、目录布局、URL 签名均属于业务环境差异。 | 在转换器中直接写 OSS 可减少调用方代码，但会绑定 SDK 与密钥配置。 |
| 自包含 JAR 与常规 JAR 并存 | 兼顾 Maven 依赖管理与直接分发需求。 | 仅提供一种 JAR 会让其中一类接入方式增加配置成本。 |

### 与常见架构方案的比较

| 方案 | 适配度 | 结论 |
| --- | --- | --- |
| MVC | 低 | 项目没有 HTTP 路由、视图或控制器；应由宿主 Spring Boot 项目在外层采用 MVC/WebFlux。 |
| 完整六边形架构 | 中等 | 当前的 `EpubResourceHandler` 已承担“端口”作用，但解析核心规模较小，拆分多个 adapter/use-case 模块会增加维护成本。若未来增加存储、任务与审计能力，可演进为完整六边形结构。 |
| 直接在转换器内集成 OSS | 低 | 会使通用库依赖云厂商、凭证和网络策略；与可复用目标相冲突。 |
| 异步并行上传媒体 | 中等 | 大量图片时可能提升吞吐，但需要资源缓存、错误聚合、线程池与取消语义。当前同步实现优先确保确定性和简洁性。 |
| 流式资源处理 | 高（未来） | 能降低大视频内存占用，但接口生命周期、重试和异步使用约束更复杂；当前 `byte[]` 更易接入。 |

### 优点、局限与演进方向

**优点**

- 对 EPUB 2/3 的常见目录格式有基础兼容，且正文顺序遵循规范。
- 核心库不绑定 Spring 或云厂商，接入和测试成本低。
- 资源策略可替换，适合不同部署环境。
- 安全 XML 配置禁止外部 DTD 与实体访问，减少不可信 EPUB 的外部解析风险。

**局限**

- CSS 解析和内联不是完整浏览器渲染引擎，复杂样式不能保证像素级一致。
- 资源处理接口目前使用 `byte[]`，不适合非常大的媒体文件。
- 不处理 CSS 背景图、`srcset`、SVG 内嵌引用，以及章节间超链接的重写。
- 转换过程没有进度回调、取消机制、资源回滚或详细结构化诊断。

**建议的后续优化**

1. 将 `EpubResource` 扩展为受控 `InputStream` 或临时文件句柄，并定义关闭责任，以支持大媒体流式上传。
2. 增加 `ConversionOptions`，显式配置 CSS 严格度、资源标签、标题策略、输出编码和错误处理方式。
3. 引入结构化的 `ConversionException`、警告列表和进度监听器，便于 Web 任务中心展示转换状态。
4. 增加覆盖 EPUB 2、EPUB 3、多章节、嵌套目录、异常 XML、重复资源、大媒体的测试夹具。
5. 若产品要求更高的版式还原度，评估专业 CSS 解析器或在输出端保留受控 CSS 文件，而不是继续增强正则规则。

### 待补充的业务决策

以下内容无法仅从当前源码确定，建议在接入项目或后续设计评审中补充：

- 允许的最大 EPUB、图片和视频大小，以及对应的超时、内存与并发限制。
- OSS 对象命名规则、访问权限（私有/公开/签名 URL）、生命周期和失败清理策略。
- 转换失败时是否保留部分 HTML/媒体，是否需要幂等键与重试机制。
- 前端展示是否要求完整字体、音视频跨域策略、富文本安全清洗与 CSP。
