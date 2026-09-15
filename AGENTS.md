# Repository guidelines

## Project overview

This is a JDK 21 Maven library that converts EPUB files into a `List<TocItem>` chapter tree. It also ships an executable shaded CLI JAR whose entry point is `cn.p4u.eth.cli.CliRunner`.

## Package boundaries

- `cn.p4u.eth`: public conversion facade.
- `cn.p4u.eth.model`: public chapter-tree model.
- `cn.p4u.eth.callback`: public progress callback contracts.
- `cn.p4u.eth.resource`: public resource strategy contracts and local implementation.
- `cn.p4u.eth.cli`: command-line adapter; keep argument parsing and exit-code handling out of the conversion core.
- `cn.p4u.eth.util`: output and general utilities.
- `cn.p4u.eth.internal.archive`: EPUB archive, XML, package, and navigation handling.
- `cn.p4u.eth.internal.content`: chapter slicing and HTML processing pipeline.
- `cn.p4u.eth.internal.pipeline`: conversion orchestration.

Do not move internal implementation types back into the root package. Preserve the facade, strategy, composite, callback, and processing-pipeline separation documented in `docs/design.md`.

## Build and verification

Maven must run on JDK 21. On this workstation the configured JDK is `C:\DevRepo\OpenJdk\dragonwell-21.0.11`.

Run the relevant focused tests while editing, then run:

```powershell
mvn clean package
```

The expected artifacts are:

- `target/epub-to-html-1.0.0.jar`: normal library JAR.
- `target/epub-to-html-1.0.0-cli.jar`: executable JAR with dependencies.

For CLI changes, verify both `CliRunnerTest` and an actual invocation:

```powershell
java -jar target/epub-to-html-1.0.0-cli.jar --help
```

## Coding conventions

- Keep public APIs small and document parameters, return values, null handling, and side effects in Chinese Javadoc.
- Add comments for design intent and non-obvious EPUB behavior; do not narrate straightforward syntax.
- Use `Path` and try-with-resources for filesystem and archive lifecycles.
- Preserve chapter-tree ordering and keep each node's content separate from its children.
- Treat `EpubResourceHandler` as the extension point for local, Base64, or remote resource storage.
- Escape generated HTML and JSON output, and retain explicit validation at external boundaries.
- New bug fixes require a regression test near the affected component.

## CLI contract

The CLI supports `-i/--input`, `-o/--output`, `-f/--format`, `-m/--media-handler`, `-d/--delete-class`, and `-h/--help`. Exit codes are `0` for success, `1` for conversion/runtime failure, and `2` for invalid arguments or input. Never allow the output file to overwrite the input EPUB.

## Release safety

- The `release` Maven profile creates sources and Javadoc JARs and signs every published artifact.
- Do not store GPG passphrases, Central tokens, or private-key material in repository files.
- Use `MAVEN_GPG_PASSPHRASE` or a primed `gpg-agent` for signing.
- Keep Shade filters for manifest and signature metadata; the final CLI manifest must still contain `Main-Class: cn.p4u.eth.cli.CliRunner`.
- `deploy-verify.bat` is currently ignored by Git and is a workstation-local helper.

## Working tree hygiene

The repository may contain an in-progress package refactor represented as deleted old paths and untracked new paths. Do not reset, restore, or delete those changes. Avoid committing Maven cache content under `.m2`; treat it as build output unless the user explicitly says otherwise.
