# CLAUDE.md

This file provides guidance to [Claude Code](https://claude.com/product/claude-code) when working with code in this repository.
It is not intended for human eyes.

### Maintenance

You (robot or human) have standing permission to update this file without asking.
Add important patterns, gotchas, or context that would help future sessions.
Keep it concise and actionable.

## Project overview

This is a Scala library for writing browser extensions against a typed model of the browser.

- `core` (Scala.js): the model (`BrowserExtension`, contexts, `Browser`, `Message`, `Shared`, `StorageKey`, type-level `Permission`s), its implementation over the WebExtensions API (`webext`), and the bundles' entry points (`launch`).
  - Every part sees a `ContentBrowser`; content scripts see a `TabBrowser` (which serves `Message.tab`s and shares `Shared.tab`s, each as a `Resource`), and the background and pages a `Browser` (whose `tabs` call and read them by tab). One `WebExtensionBrowser` implements both.
  - Values go as JSON through circe's `Encoder` and `Decoder`, which `Message`, `Shared.Tab` and `StorageKey` take as context bounds and keep as a `Codec`. `webext.Circe` converts between them and the browser's `js.Any`, which the testkit uses too: to it through `circe-scalajs`, but from it with its own conversion, as `circe-scalajs`'s tells objects and arrays apart with `instanceof`, which fails for those of another realm, as each built extension's sandbox gets from the simulation's `JSON.parse`.
  - `languages.detect` is `i18n.detectLanguage` (every part, no permission). Safari has none (MDN's browser-compat-data: `false`), and Firefox for Android's threw before 106, so `WebExtensionLanguages` gives `Detection.none` where the function is missing, throws or rejects. The testkit's `FakeI18n` has no `detectLanguage` when built for Safari, and otherwise finds what `FakeBrowser.detectLanguages` says (nothing by default), all of a text, reliably.
  - `WebExtensionStorage.read`, which the testkit's `FakeBrowser.stored` shares, gives a key's default for a stored value that can't be decoded, and warns with `console.warn`: a built sandbox's console fails the test on `console.error`, but not on a warning.
  - A tab request is `tabs.sendMessage` to frame `0`. Each page's shared values go through one `Hub` (`webext/Sharing.scala`), kept in a `WeakMap` under `globalThis.__omnibrowserShared` by the world's `runtime` object: every copy of the content bundle in a page (one per `ContentScript` that matches it) uses it, while in-process tests, whose tabs share one global, get one per content context, as each has its own `FakeRuntime`. It alone listens for ports (`omnibrowser/shared/<name>`) and reads (`omnibrowser/shared`, the name as the request), so it answers each: with the owner's (the latest sharer's) `{value}`s, or `{unshared}`, keeping the port to give to a later owner. On being made, it announces `omnibrowser/sharing` to the background and pages, and again on `pageshow` with `persisted` (back-forward cache, which closes ports) and on `prerenderingchange`. An owner's stop clears a flag at once, which each post checks, so nothing it sends lands after the hub's `{unshared}`.
  - `Interop.promise` handles each promise at once. A program that `Launcher.default` runs synchronously only waits for the promise after its first asynchronous step, so a quick rejection (the fake's are synchronous) would otherwise be unhandled, which crashes Node and logs "Uncaught (in promise)" in browsers.
  - Only built does each `ContentScript` matching a page load its own copy of the content bundle into one scope, and no test puts two on one page built, so nothing checks the hub across copies (a test-only `sharing` project did, and was removed). Keep the hub on `globalThis`: in-process tests can't tell it from a hub in module state (a Scala `object`), of which each copy would have its own.
  - A watcher (`WebExtensionTabs.values`) listens for its tab's announcements before connecting, and reconnects on each (`switchMap`), so it follows late sharers and navigation. Its port's `onDisconnect` reads `runtime.lastError`, or Chromium logs "Unchecked runtime.lastError". An owner treats a failed `postMessage` as the port gone (Firefox may not say), and drops it from the hub.
- `sbt-omnibrowser` (JVM, sbt 2 plugin): links two classic-script bundles from the same code, then runs the main one under Node to have it write its own `manifest.json`, bootstraps and HTML for each target (`launch.Describe`).
  The main bundle (`omnibrowser.js`, from `launch.Entry`) runs the background and pages; the content scripts' own (`omnibrowser-content.js`, from `launch.ContentEntry`, linked by `linkContent`) holds only what they reach, and is dropped if there are none.
  A release's bundles are minified (`Minifier`) by esbuild, pinned, through `npx` (`cmd /c npx` on Windows, as `Npm` runs npm), into `<linker output>-minified` beside each, before they are laid out; the linker's own output stays unminified, for measuring what each package adds by its names (`$c_L…`). Scala.js 1.21 turned the Closure Compiler off and deprecated it, and its own `minify` shortens only properties' names, keeping the long global names and the indentation: Code Switch's main bundle was 6.49 MB so, 2.22 MB minified. The output is UTF-8 (`--charset=utf8`), not ASCII, which every context reads as such. `zip` then fails (`Assembly.check`) if Firefox's layout has a file that Firefox Add-ons will not parse, a `.html`, `.htm`, `.js`, `.mjs` or `.json` of 5 MiB or more (addons-linter's `FILE_TOO_LARGE`, an error, which rejected Code Switch 0.2.0). Development builds are neither minified nor checked.
  Each context loads a small bootstrap, naming its role, before its bundle.
  The targets (`Target`, in both `core` and the plugin) are `Chromium` (every browser built on it, as Chrome and Edge take the same zip), `Firefox` (desktop and Android) and `Safari` (macOS and iOS). Chromium and Safari run the background as a service worker that `importScripts` the bundle, as iOS needs a background that it may stop; Firefox runs it as scripts. The API's own names stay Chrome's (the `chrome` global, `chrome-extension://`).
- `testkit` (Scala.js): `FakeBrowser`, an in-memory fake of the WebExtensions API (`FakeApi`, one per context, over shared `Simulation` state).
  `InProcess` runs an extension's code through the real `Launcher` and `WebExtensionBrowser`; `Built` runs the plugin's output in Node `vm` sandboxes.
  Actions settle by polling `Activity` until it has been idle for a moment.
  Tabs and pages have jsdom documents (`Dom`) if the tests can load jsdom from `node_modules`, or else `FakeDocument` stand-ins.
  In-process, every document belongs to one shared jsdom window, whose interfaces (`HTMLElement`, ...) and `window` are made globals of the test, and they take turns as the global `document`; built, each tab and page is a jsdom window of its own, which is its sandbox's scope.
- `laminar` (`omnibrowser-laminar`, Scala.js): `LaminarPage` and `LaminarContentScript`, which render an element into the body, and extension methods on `ContentBrowser` connecting storage keys, `IO`s and fs2 streams to Airstream (`signal`, `writer`, `updater`, `events`, `observer`).
  `updater`'s transform takes the event, as Airstream's `Var.updater` does. Scala cannot infer the event's type from `-->`, so it is `Any` unless the transform annotates it.
  An fs2 stream runs through `browser.run` (so the testkit tracks it) while its event stream is started, and a `Deferred` interrupts it on stop.
  `signal`'s two overloads (a key's, and a tab's value's) share one generic receiver, `[B <: ContentBrowser]`, the tab's asking for `B <:< Browser`: Scala picks among extension overloads by receiver before the arguments, so a separate `extension (browser: Browser)` hides `signal(key)` from pages, and duplicating it there is ambiguous.
- `example`: an extension built with both, whose popup is Laminar's, and tested with the testkit in both ways. The output is under `target/out/sjs1/scala-3.9.0/omnibrowser-example/omnibrowser/`.
- Each subproject's `README.md` holds its installation and usage in detail, mostly in tables. The main README gives only the plugin's installation, one example and a table of the subprojects, each described in a line and linked to its README; links go from the main README to the subprojects', never back. Keep each current as its subproject changes, and give a new subproject one, with a row in that table.
- The plugin makes every test run build the extension first (through `Test / jsEnvInput`, as `test` is an input task in sbt 2), and tells the tests where it is through `OMNIBROWSER_BUILD`.

- `launch.AndroidSupport` lists the permissions that Firefox for Android lacks, from MDN's browser-compat-data (`webextensions/manifest/permissions.json` and `optional_permissions.json`, where `firefox_android` is `false`). Update it from there as permissions are modelled.

### Gotchas

- Many files are synced from `SgtSwagrid/scala-library-config` by GitHub Graph, which reverts a change made here: each that it has but `README.md` and `build.sbt`, such as `.gitignore`, `CONTRIBUTING.md`, `docs/STYLE_GUIDE.md`, `.github/**` (CI included), `project/IdeSettings.scala`, `project/build.properties` and `project/plugins-{common,library}.sbt`. Change them there.
- The tests' jsdom comes from npm, through `package.json` and `package-lock.json`. CI's workflow is synced, so the build installs it: `Npm.npmInstall` runs `npm ci` before the tests of each project with `Npm.beforeTests`, unless `node_modules` is newer than the lockfile.
  jsdom is pinned at 29.1.1, as jsdom 30 needs Node 22.22.2 or 24.15, newer than this machine's.
- jsdom has no `innerText`, so `Dom` gives its elements one that is their `textContent`.
- Laminar is at a milestone, 18.0.0-M5, the latest on GitHub when it was added.

- The plugin's sources are also compiled into the build definition (`project/plugins-omnibrowser.sbt`), so the build can use its own plugin. A broken plugin breaks loading the build, and the plugin must compile with sbt's own Scala version (3.8.4).
- The published plugin adds only `omnibrowser-core`, at the version in its jar's manifest (`PluginVersion`): the testkit and `omnibrowser-laminar` are published apart, and an extension adds them itself at `omnibrowserVersion`. Compiled into this build's definition it has no version, so it adds nothing, and the example depends on the projects directly.
- In sbt 2, a task returning `File` must have its key marked `@transient`, or be wrapped in `Def.uncached`.
- An extension's parts are methods (`def background()`, `contentScripts()`, `popup()`, `options()`) so that the content bundle reaches only `contentScripts()` and the object's own vals. Never make them vals (Scala 3 rejects a `val` overriding them anyway), and keep `launch.Bootstrap.startContent` from reaching the other parts.
- In sbt 2, `test` runs only the tests that changes may affect, and those that failed; `testFull` runs them all.
- Format with `scalafmtAll`, which CI checks. Not with `scalafmtSbt`, which would reformat `build.sbt` and `project/*.scala` beyond what you changed, as `main` leaves them unformatted.
- A `/*` in a comment, as in a match pattern like `https://*/*`, opens a nested comment in Scala.
- CI's sbt has a 1 GB heap, which cannot hold every project's linker's incremental state at once: once a `sharing` project (since removed) added three more linkers, the test step ran out of heap and hung until it timed out, with no log. So every Scala.js project links in batch mode on CI (`Linking.batchOnCi`, when `CI` is set), keeping no state, and `build.sbt` limits `ScalaJSTags.Link` to two at once, which the plugin's content scripts' link is tagged as. To reproduce CI here, run sbt with `-Xmx1g`, pinned to four cores (`cmd /c start /affinity F /wait /b java -Xmx1g -jar ...`), with `CI` set or not. A cancelled run's log survives, as a timed-out one's may not.
- On this Windows machine, `sbt` cannot start its server (the space in the Java path). Run it in-process instead: `java -jar "C:/Program Files (x86)/sbt/bin/sbt-launch.jar" <commands>`.
  The machine is often short of memory: an `OutOfMemoryError`, a `hs_err_pid*.log`, or Node exiting with `-1073740791` means retrying later or with a smaller `-Xmx`, not a bug. Delete any `hs_err_pid*.log` and `replay_pid*.log` left behind.
- sbt 2 restores outputs from its cache as symlinks, which Windows cannot move without a privilege, so a later recompile can fail with `FileSystemException: ... A required privilege is not held by the client`. Run `<project>/clean` and retry.
- fs2's `interruptWhen(stream)` interrupts once the halting stream ends, even without emitting `true`. To interrupt only on some outcome, pass an `IO[Either[Throwable, Unit]]` that never completes otherwise.
- The testkit delivers port messages, connections and disconnections with `Simulation.later` (a `setTimeout`, counted as pending by `Activity`), as a port's listeners are only added after `connect` returns. Its ports, like the browser's, throw on posting once disconnected. A port that reaches nothing fails the test with `UncheckedError` unless its listener reads `runtime.lastError`. A message that reaches no listener is rejected, as the browser's is. `FakeBrowser.close` and `navigate` destroy a tab's content context: its ports disconnect, its unanswered requests fail, it hears nothing more, and its messages and storage calls are rejected ("Extension context invalidated."), though its fibers run on, in-process and built alike. A closed tab is reported through `tabs.onRemoved`. Every event the simulation fires goes through `tell`, so one listener's failure is the extension's without silencing the others. The fake has no back-forward cache, and models Chromium's ports for Firefox's and Safari's builds too.
- In a non-native JS class, a qualified private member (`private[testkit]`) must be marked `final`, even in a `final class`.
- A class nested in a companion and named like a top-level class (`Message.Tab`, `Shared.Tab` and `Tab`) must be made with `new` within it, or the constructor proxy is ambiguous.
- A `Message[Req, Res]` is a kind of message (a name and codecs), which used to be called `Endpoint`, as the browser's messages never leave it. A raw `js.Any` that the browser carries stays `message` at the WebExtensions layer (facades, `FakeApi`), but is `json` in `webext.Messages`, where both meet.
- `unidoc` cannot resolve `js.JSON.stringify` (an overloaded Scala.js method with defaults), though each project's own Scaladoc can, so the release's docs fail on it. Use `launch.Json.stringify` instead.
- In a non-native JS class (`extends js.Object`), a Scala-side check like `x.isInstanceOf[FakeDocument]` on a `js.Dynamic` warns that it is always false; widen `x` to `Any` first.
- A built extension can be checked in Firefox (installed here) headless, over WebDriver BiDi: start it with `--remote-debugging-port` and a fresh `--profile`, connect to `ws://127.0.0.1:<port>/session`, `session.new`, then `webExtension.install` with `{type: "path"}`. BiDi refuses to navigate a tab to `moz-extension://` pages, so reach the extension from a content script on a local page (one that writes to the page's DOM), which can read storage and message the background. A `user.js` setting `extensions.webextensions.uuids` fixes the extension's UUID.

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
- Use the `gh` CLI to do the above.
