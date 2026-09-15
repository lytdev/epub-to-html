# 从一次转换理解项目设计

本库把 EPUB 转成 `List<TocItem>`，每个节点保存自己的 HTML 正文。设计的重点是让读取、切分、加工和资源存储各自有清晰边界。

## 采用哪些模式

| 模式或结构 | 项目中的位置 | 解决的问题 |
| --- | --- | --- |
| 门面 Facade | `EpubConverter` | 调用方只需选择文件或输入流，无需了解内部组件。 |
| 策略 Strategy | `EpubResourceHandler`、`LocalResourceHandler` | 替换本地保存、云上传、Base64 编码时，解析流程不用修改。此边界原已存在，本次保留。 |
| 组合 Composite | `TocItem.children` | 普通章节、叶子和无链接分组使用统一节点表示，递归访问所有层级。采用同一种节点的简化实现，无额外叶子类。 |
| 有序管道 Pipeline | `FragmentPipeline` | 将媒体替换、图注组合、属性清理按明确顺序组合；某一步失败即传播异常。 |

管道是处理流程的组织方式，并不等同于 GoF 职责链：这里每一步都要执行，不是寻找一个匹配的处理者。只有一个实现的内部工具保持普通类，避免为了模式增加工厂、单例或继承层次。

## 阅读路线

1. `EpubConverter.convert`：参数校验和输入适配；输入流先暂存，由 try-with-resources 自动清理。
2. `ConversionPipeline.convert`：打开 ZIP，读取包描述和目录，无目录时按 spine 建树，结束时关闭 ZIP。
3. `ChapterReadPlan.collect`：先序遍历目录，清空旧 content，再按文件分组。相同文件只解析一次，目录树本身的顺序不变。
4. `TocContentReader.fillFile`：读取完整 DOM，先内联样式，再按目录锚点的实际位置切分。
5. `ChapterRange.copy`：复制半开区间 `[开始, 结束)` 中的节点，保留必要祖先；同位置重复目标只让最后一项取得正文。
6. `FragmentPipeline.process`：依次替换媒体、组合图注、按选项清除 class/id，最后返回 HTML。
7. `ResourceResolver.resolve`：从 ZIP 读取资源，调用存储策略，验证 URL 后缓存成功结果。

关键依赖方向：`TocContentReader → FragmentPipeline → MediaProcessor → ResourceResolver → EpubResourceHandler`。资源读取器不依赖 DOM，因此可以单独测试失败、去重和跨转换隔离。

## 为什么顺序不能随意调整

完整 DOM 上的 CSS 选择器可能依赖父元素。若先切成片段，再匹配 CSS，会改变样式。目录锚点依赖 id，所以也不能在切分前清理属性。片段内先替换图片地址，再组合 figure，最后清理标识属性。

例如父章指向 `a.xhtml`、子节指向 `a.xhtml#part`：父章只取得 body 开始到 part 之前的内容，子节从 part 开始。两者即使引用同一图片，也只在本次转换中调用一次存储策略。

## 状态与异常

`EpubConverter` 无可变实例状态。归档与资源缓存的生命周期均为一次转换。不同书籍可能都含 `images/cover.png`，因此不能把缓存设为 static 或单例。调用方的资源处理器若跨线程共享，需要自行保证线程安全。

缺失可选媒体会保留原引用；缺失正文或导航锚点会抛出 IOException。上传异常或空白 URL 也会终止转换，不会把失败结果放进缓存。先前已经上传的文件不会自动回滚，宿主负责补偿。

## 如何扩展

- 新存储方式：实现 `EpubResourceHandler.handle`，同步返回可用 URL；可参考 `LocalResourceHandler`。
- 新片段处理规则：实现包内处理方法，在 `FragmentPipeline` 中按依赖关系插入步骤；若规则依赖完整 DOM，应放到裁剪前。
- 修改内容归属：查看 `TocContentReader` 与 `ChapterRange`，不要在资源策略中改变章节树。
- 展示标题：读取 `TocItem.getLabel()`；当前转换不调用历史 `HeadingProcessor`，不会自动把 label 插入正文。

## 用测试学习

运行 `mvn test`，确保 Maven 使用 JDK 21。`EpubFixture` 在临时目录生成小型 EPUB，全部测试均不依赖 E 盘或未提交的 demo.epub，也不向固定目录写 HTML。

先读 `EpubConverterTest` 学习公开调用，再看 `ConversionPipelineTest` 的阶段顺序与异常、`TocContentReaderTest` 的父子内容归属，最后看 `ResourceResolverTest` 的缓存与失败契约。生成的测试归档旨在验证特定规则，真实复杂书籍仍适合另做手工兼容性验证。

## 包边界与依赖方向


### 包迁移说明

这是源码和二进制不兼容的包迁移，已有宿主需要更新 import 并重新编译。Maven 坐标和 convert 方法参数语义保持不变；不保留同名转发类，以免形成两套模型。

| 类型 | 新包 |
| --- | --- |
| EpubConverter | cn.p4u.eth（不变） |
| TocItem | cn.p4u.eth.model |
| EpubResource、EpubResourceHandler、LocalResourceHandler | cn.p4u.eth.resource |
| FileParseCallback、CallBackRecord | cn.p4u.eth.callback |
| CliRunner | cn.p4u.eth.cli |

`internal.*` 下部分类因跨包调用声明为 public，但属于内部实现，不承诺兼容。内容处理器、资源会话、区间裁剪等继续保持包内可见。测试按被测组件放在对应包，共享构造数据和断言工具在测试专用的 `cn.p4u.eth.support` 包中。

各包按职责分组；设计模式解释协作方式，不用于给每个类强行添加接口。

```text
cli → EpubConverter → internal.pipeline → internal.content → internal.archive
cli → util → model（展示 HTML / JSON 输出）
                   ↘ internal.archive（输入流适配）
internal.pipeline → internal.archive（包描述与导航）
内部实现 → model / resource / callback（公开契约）
```

model 不依赖解析实现；callback 仅引用模型；resource 不依赖门面或内部流程。包内 Javadoc 描述职责、扩展位置和状态生命周期。

`util.TocSupport` 是公开输出辅助工具，会根据 label 增加展示标题；它与 `internal.content.TocSupport` 的内部正文汇总用途不同。转换门面本身仍只返回内容树。

目录树与包名以 README 的项目组织结构为准。测试使用与被测组件相同的包，因此无需为了测试开放所有内部方法；support 只在 src/test 中，不进入发布 JAR。
