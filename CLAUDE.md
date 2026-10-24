# CLAUDE.md

This file provides guidance to [Claude Code](https://claude.com/product/claude-code) when working with code in this repository.
It is not intended for human eyes.

### Maintenance

You (robot or human) have standing permission to update this file without asking.
Add important patterns, gotchas, or context that would help future sessions.
Keep it concise and actionable.

## Project overview

This is a Scala library for writing browser extensions against a typed model of the browser.

- `core` (Scala.js): the model (`BrowserExtension`, contexts, `Browser`, `Endpoint`, `StorageKey`, `Codec`, type-level `Permission`s), its implementation over the WebExtensions API (`webext`), and the bundle's entry point (`launch`).
- `sbt-omnibrowser` (JVM, sbt 2 plugin): links one classic-script bundle shared by every context, then runs it under Node to have it write its own `manifest.json`, bootstraps and HTML for each target (`launch.Describe`).
  Each context loads a small bootstrap, naming its role, before the bundle.
- `example`: an extension built with both, whose `testFull` runs `omnibrowserBuild` first. The output is under `target/out/sjs1/scala-3.9.0/omnibrowser-example/omnibrowser/`.

### Gotchas

- The plugin's sources are also compiled into the build definition (`project/plugins-omnibrowser.sbt`), so the build can use its own plugin. A broken plugin breaks loading the build, and the plugin must compile with sbt's own Scala version (3.8.4).
- In sbt 2, a task returning `File` must have its key marked `@transient`, or be wrapped in `Def.uncached`.
- A `/*` in a comment, as in a match pattern like `https://*/*`, opens a nested comment in Scala.
- On this Windows machine, `sbt` cannot start its server (the space in the Java path). Run it in-process instead: `java -jar "C:/Program Files (x86)/sbt/bin/sbt-launch.jar" <commands>`.

## Instructions

### Compilation and Diagnostics

- When the user asks for help with a compilation or type error, start by running `sbt compile` to see the error for yourself.
  If there are many errors, making it unclear which one the user is referring to, ask them to clarify, and then focus only on that issue.
- IntelliJ MCP integration is active. When a request seems to implicitly refer to something the user is looking at, always check
  `mcp__ide__getDiagnostics` first to see which file(s) are open and get associated diagnostics (errors, warnings, and info hints with line numbers).

#### Testing

- After making code changes, always run `sbt compile` to verify that issues are fixed and no new ones are introduced.
- Repeatedly retry upon failure until the build succeeds. If you are unsure how to fix an issue, ask for help or refer to existing code for examples.
- Before trying to fix an error, make sure you first understand it fully.
- You should never report that a feature is complete without testing it first.

### Code Style

- You must read the [Code Style Guidelines](docs/STYLE_GUIDE.md).

### Pull Requests

When asked to publish the code changes, your task is to open one or more pull requests (PRs) to merge the changes into `main` on GitHub:

- Use `git` to check what has changed as compared to the `main` branch on `origin`.
- If the changes are thematically linked, they can be published as a single PR.
- Otherwise, you'll need to divide the changes into multiple PRs using your own judgement.
- Each PR should have a singular focus, shouldn't break anything, and should be able to be merged independently.
- Ensure that all code is staged, committed and pushed. Ensure no new files are left uncommitted, and no debug code is left in the codebase.
- When creating a PR, ensure that the title and description are clear, informative, and comprehensive.
- All feature/bugfix/etc branch names should be formatted as "feature_<short description>" or "fix_<short description>" or similar.
- All PR titles should be formatted as "[<scope>] <Short summary>", e.g. "[renderer] Fixed colour inversion bug."
- You have GitHub MCP integration that can be used to do the above.
