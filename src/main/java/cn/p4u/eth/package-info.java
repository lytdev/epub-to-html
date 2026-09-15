/**
 * EPUB 章节解析库。
 *
 * <p>建议从 {@link cn.p4u.eth.EpubConverter} 门面开始，沿 ConversionPipeline、
 * TocContentReader、ChapterReadPlan、FragmentPipeline 阅读处理流程。
 * EpubResourceHandler 是对外的策略接口，TocItem 是递归组合的返回模型。
 *
 * <p>生命周期约定：一次转换拥有一个 ZIP 归档和一份资源缓存；返回前关闭归档，
 * 返回的正文和媒体描述不持有打开的文件流。调用方传入的 InputStream 由调用方关闭。
 * 包内组件是实现细节，宿主通过公开门面与资源策略使用本库。
 */
package cn.p4u.eth;
