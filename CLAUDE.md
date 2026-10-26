# CLAUDE.md

This file provides guidance to [Claude Code](https://claude.com/product/claude-code) when working with code in this repository.
It is not intended for human eyes.

### Maintenance

You (robot or human) have standing permission to update this file without asking.
Add important patterns, gotchas, or context that would help future sessions.
Keep it concise and actionable.

## Project overview

This is a Scala library for writing browser extensions against a typed model of the browser.

- `core` (Scala.js): the model (`BrowserExtension`, contexts, `Browser`, `Endpoint`, `StorageKey`, `Codec`, type-level `Permission`s), its implementation over the WebExtensions API (`webext`), and the bundles' entry points (`launch`).
- `sbt-omnibrowser` (JVM, sbt 2 plugin): links two classic-script bundles from the same code, then runs the main one under Node to have it write its own `manifest.json`, bootstraps and HTML for each target (`launch.Describe`).
  The main bundle (`omnibrowser.js`, from `launch.Entry`) runs the background and pages; the content scripts' own (`omnibrowser-content.js`, from `launch.ContentEntry`, linked by `linkContent`) holds only what they reach, and is dropped if there are none.
  Each context loads a small bootstrap, naming its role, before its bundle.
- `testkit` (Scala.js): `FakeBrowser`, an in-memory fake of the WebExtensions API (`FakeApi`, one per context, over shared `Simulation` state).
  `InProcess` runs an extension's code through the real `Launcher` and `WebExtensionBrowser`; `Built` runs the plugin's output in Node `vm` sandboxes.
  Actions settle by polling `Activity` until it has been idle for a moment.
- `example`: an extension built with both, and tested with the testkit in both ways. The output is under `target/out/sjs1/scala-3.9.0/omnibrowser-example/omnibrowser/`.
- The plugin makes every test run build the extension first (through `Test / jsEnvInput`, as `test` is an input task in sbt 2), and tells the tests where it is through `OMNIBROWSER_BUILD`.

- `launch.AndroidSupport` lists the permissions that Firefox for Android lacks, from MDN's browser-compat-data (`webextensions/manifest/permissions.json` and `optional_permissions.json`, where `firefox_android` is `false`). Update it from there as permissions are modelled.

### Gotchas

- The plugin's sources are also compiled into the build definition (`project/plugins-omnibrowser.sbt`), so the build can use its own plugin. A broken plugin breaks loading the build, and the plugin must compile with sbt's own Scala version (3.8.4).
- The published plugin adds `omnibrowser-core` and `omnibrowser-testkit` at the version in its jar's manifest (`PluginVersion`). Compiled into this build's definition it has none, so it adds nothing, and the example depends on the projects directly.
- In sbt 2, a task returning `File` must have its key marked `@transient`, or be wrapped in `Def.uncached`.
- An extension's parts are methods (`def background()`, `contentScripts()`, `popup()`, `options()`) so that the content bundle reaches only `contentScripts()` and the object's own vals. Never make them vals (Scala 3 rejects a `val` overriding them anyway), and keep `launch.Bootstrap.startContent` from reaching the other parts.
- A `/*` in a comment, as in a match pattern like `https://*/*`, opens a nested comment in Scala.
- On this Windows machine, `sbt` cannot start its server (the space in the Java path). Run it in-process instead: `java -jar "C:/Program Files (x86)/sbt/bin/sbt-launch.jar" <commands>`.
  The machine is often short of memory: an `OutOfMemoryError`, a `hs_err_pid*.log`, or Node exiting with `-1073740791` means retrying later or with a smaller `-Xmx`, not a bug. Delete any `hs_err_pid*.log` and `replay_pid*.log` left behind.
- sbt 2 restores outputs from its cache as symlinks, which Windows cannot move without a privilege, so a later recompile can fail with `FileSystemException: ... A required privilege is not held by the client`. Run `<project>/clean` and retry.
- `unidoc` cannot resolve `js.JSON.stringify` (an overloaded Scala.js method with defaults), though each project's own Scaladoc can, so the release's docs fail on it. Use `launch.Json.stringify` instead.
- In a non-native JS class (`extends js.Object`), a Scala-side check like `x.isInstanceOf[FakeDocument]` on a `js.Dynamic` warns that it is always false; widen `x` to `Any` first.

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
