package cn.p4u.eth.cli;

import cn.p4u.eth.EpubConverter;
import cn.p4u.eth.callback.CallBackRecord;
import cn.p4u.eth.callback.FileParseCallback;
import cn.p4u.eth.model.TocItem;
import cn.p4u.eth.resource.EpubResourceHandler;
import cn.p4u.eth.resource.LocalResourceHandler;
import cn.p4u.eth.util.TocSupport;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;

/**
 * Linux/Windows 通用命令行入口。
 *
 * <p>命令行层只负责参数、输出目录和退出码；EPUB 解析仍由 {@link EpubConverter} 门面完成。 这种分层使库 API 与命令行界面可以独立演进。
 */
public final class CliRunner {
  private static final String USAGE =
      """
      用法:
        java -jar epub-to-html-1.0.0-cli.jar -i <input.epub> [-o <output>] [-f <html|json>] [-m <base64|local>] [-d]

      参数:
        -i, --input <file>           输入 EPUB 文件
        -o, --output <path>          输出文件或目录；省略时写到输入文件所在目录
        -f, --format <html|json>     输出格式，默认 html
        -m, --media-handler <base64|local> 资源处理方式，默认 local
        -d, --delete-class           删除正文元素的 class 和 id
        -h, --help                   显示帮助

      输出:
        目录模式: <output>/chapters.html 或 <output>/chapters.json，以及 <output>/assets/
        文件模式: 写入指定文件；local 模式下资源保存到该文件所在目录的 assets/
      """;

  private CliRunner() {}

  /** JVM 入口；退出码 0 表示成功，2 表示参数错误，1 表示转换失败。 */
  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  /** 执行命令并返回退出码。独立方法便于测试，无需在测试进程中调用 System.exit。 */
  static int run(String[] args, PrintStream out, PrintStream err) {
    final Options opts;
    try {
      opts = Options.parse(args);
    } catch (UsageException ex) {
      err.println(ex.getMessage());
      err.println();
      err.println(USAGE);
      return 2;
    }
    if (opts.help) {
      out.println(USAGE);
      return 0;
    }
    if (opts.input == null) {
      err.println("错误: 缺少输入文件");
      err.println();
      err.println(USAGE);
      return 2;
    }

    Path input = opts.input.toAbsolutePath().normalize();
    if (!Files.exists(input)) {
      err.println("错误: 输入文件不存在: " + input);
      return 2;
    }
    if (!Files.isRegularFile(input) || !Files.isReadable(input)) {
      err.println("错误: 输入路径不是文件或者文件不可读: " + input);
      return 2;
    }

    try {
      // 输出路径：目录模式按格式生成默认文件名，文件模式直接使用指定文件。
      Path outputFile;
      if (isOutputDirectory(opts.output)) {
        Path outputDir = opts.output == null ? defaultOutputDir(input) : opts.output;
        outputFile = outputDir.resolve("chapters." + opts.format);
      } else {
        outputFile = opts.output;
      }
      outputFile = outputFile.toAbsolutePath().normalize();
      if (outputFile.equals(input)) {
        throw new UsageException("错误: 输出文件不能覆盖输入 EPUB: " + input);
      }
      requireMatchingExtension(outputFile, opts.format);

      Path outputDir = outputFile.getParent();
      if (outputDir == null) {
        outputDir = Paths.get(".");
      }
      Files.createDirectories(outputDir);

      // 资源处理模式：base64 内嵌到 HTML，local 保存到 assets 目录。
      EpubResourceHandler resourceHandler;
      if ("base64".equals(opts.mediaHandler)) {
        resourceHandler =
            resource ->
                "data:"
                    + resource.mediaType()
                    + ";base64,"
                    + Base64.getEncoder().encodeToString(resource.content());
      } else {
        Path assets = outputDir.resolve("assets");
        resourceHandler = new LocalResourceHandler(assets, "assets");
      }

      List<TocItem> itemList =
          new EpubConverter().convert(input, resourceHandler, opts.deleteClass, progress(out));

      String outputStr =
          "json".equals(opts.format)
              ? TocSupport.writeToJson(itemList)
              : TocSupport.contentToHtml(itemList, true);

      Files.writeString(outputFile, outputStr, StandardCharsets.UTF_8);
      out.println("转换完成: " + outputFile);
      return 0;
    } catch (UsageException ex) {
      err.println(ex.getMessage());
      return 2;
    } catch (Exception ex) {
      err.println("转换失败: " + ex.getMessage());
      return 1;
    }
  }

  /** 输入文件所在的目录；相对路径解析不到父目录时退回当前目录。 */
  private static Path defaultOutputDir(Path input) {
    Path parent = input.toAbsolutePath().normalize().getParent();
    return parent == null ? Paths.get(".") : parent;
  }

  /**
   * 判断输出路径按目录处理还是文件处理。
   *
   * <p>目录模式：路径为 null、已是目录，或不存在且不以 {@code .html}/{@code .json} 结尾；
   * 文件模式：路径已是文件，或不存在但文件名以 {@code .html}/{@code .json} 结尾。
   */
  private static boolean isOutputDirectory(Path output) {
    if (output == null) {
      return true;
    }
    if (Files.isDirectory(output)) {
      return true;
    }
    if (Files.exists(output)) {
      return false;
    }
    String name = output.getFileName().toString().toLowerCase();
    return !name.endsWith(".html") && !name.endsWith(".json");
  }

  /** 当输出文件显式使用受支持的扩展名时，防止扩展名与内容格式不一致。 */
  private static void requireMatchingExtension(Path outputFile, String format) {
    String name = outputFile.getFileName().toString().toLowerCase();
    if ((name.endsWith(".html") && !"html".equals(format))
        || (name.endsWith(".json") && !"json".equals(format))) {
      throw new UsageException("错误: 输出文件扩展名与 --format " + format + " 不一致: " + outputFile);
    }
  }

  private static FileParseCallback<TocItem> progress(PrintStream out) {
    return new FileParseCallback<>() {
      @Override
      public void onLineParsed(int count, int total, CallBackRecord<TocItem> record) {
        String name =
            record.label() == null || record.label().isBlank() ? record.target() : record.label();
        out.printf("[%d/%d] %s%n", count, total, name);
      }

      @Override
      public void onError(Exception ex, int line) {
        /* run 统一输出错误，避免重复。 */
      }

      @Override
      public void onComplete(int total, String message) {
        /* 文件写入成功后由 run 输出最终完成消息，避免写入失败时提前报告成功。 */
      }
    };
  }

  /** 解析后的命令行选项。 */
  static final class Options {
    Path input;
    Path output;
    boolean deleteClass;
    boolean help;
    String format = "html";
    String mediaHandler = "local";

    /**
     * 解析命令行参数。
     *
     * @param args 原始命令行参数
     * @return 解析后的选项
     * @throws UsageException 参数非法时抛出
     */
    static Options parse(String[] args) {
      if (args == null) {
        throw new UsageException("参数列表不能为 null");
      }
      Options opts = new Options();
      int i = 0;
      while (i < args.length) {
        String arg = args[i];
        String name = arg;
        String inline = null;
        int eq = arg.indexOf('=');
        if (arg.startsWith("--") && eq > 0) {
          name = arg.substring(0, eq);
          inline = arg.substring(eq + 1);
        }

        switch (name) {
          case "-h", "--help":
            requireFlagWithoutValue(inline, name);
            opts.help = true;
            break;
          case "-i", "--input":
            opts.input = parsePath(consume(args, i, inline, name));
            if (inline == null) {
              i++;
            }
            break;
          case "-o", "--output":
            opts.output = parsePath(consume(args, i, inline, name));
            if (inline == null) {
              i++;
            }
            break;
          case "-d", "--delete-class":
            requireFlagWithoutValue(inline, name);
            opts.deleteClass = true;
            break;
          case "-m", "--media-handler":
            opts.mediaHandler = consume(args, i, inline, name);
            requireValue(opts.mediaHandler, name, "base64", "local");
            if (inline == null) {
              i++;
            }
            break;
          case "-f", "--format":
            opts.format = consume(args, i, inline, name);
            requireValue(opts.format, name, "html", "json");
            if (inline == null) {
              i++;
            }
            break;
          default:
            if (arg.startsWith("-")) {
              throw new UsageException("未知参数: " + arg);
            }
            if (opts.input != null) {
              throw new UsageException("多余的参数: " + arg);
            }
            opts.input = parsePath(arg);
            break;
        }
        i++;
      }
      return opts;
    }

    /** 校验枚举型参数只能取允许的值。 */
    private static void requireValue(String value, String name, String... allowed) {
      for (String candidate : allowed) {
        if (candidate.equals(value)) {
          return;
        }
      }
      throw new UsageException(
          "参数 " + name + " 只能取 " + String.join(" 或 ", allowed) + ": " + value);
    }

    /** 读取参数值：优先取 {@code --key=value} 内联值，否则取下一个参数。 */
    private static String consume(String[] args, int index, String inline, String name) {
      if (inline != null) {
        if (inline.isEmpty()) {
          throw new UsageException("参数 " + name + " 缺少值");
        }
        return inline;
      }
      if (index + 1 >= args.length || args[index + 1].startsWith("-")) {
        throw new UsageException("参数 " + name + " 缺少值");
      }
      return args[index + 1];
    }

    /** 布尔开关不接收值，避免把 {@code --help=false} 等拼写误认为有效。 */
    private static void requireFlagWithoutValue(String inline, String name) {
      if (inline != null) {
        throw new UsageException("参数 " + name + " 不接收值");
      }
    }
  }

  /** 将字符串转换为路径，非法路径抛出使用异常。 */
  private static Path parsePath(String value) {
    try {
      return Paths.get(value);
    } catch (InvalidPathException e) {
      throw new UsageException("非法的路径: " + value);
    }
  }

  /** 命令行参数解析错误。 */
  private static final class UsageException extends IllegalArgumentException {
    UsageException(String message) {
      super(message);
    }
  }
}
