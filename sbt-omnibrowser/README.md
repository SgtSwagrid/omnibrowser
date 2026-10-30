# 🔧 sbt-omnibrowser

The sbt plugin that builds an extension written with Omnibrowser, for Chromium, Firefox and Safari.

## ⬇️ Installation

```scala
// project/plugins.sbt
addSbtPlugin("com.alecdorrington" % "sbt-omnibrowser" % "0.6.0")

// build.sbt
lazy val extension = project.enablePlugins(OmnibrowserPlugin)
```

It adds only [`omnibrowser-core`](../core), whose entry points it builds the extension from, to the project at `omnibrowserVersion`.
The [testkit](../testkit) and [`omnibrowser-laminar`](../laminar) are the project's to add, at the same version.

## 🚀 Usage

| Task | Builds the extension | Into |
| --- | --- | --- |
| `omnibrowserBuild` | For development, unpacked for each browser, ready to load | `target/**/omnibrowser/development/<browser>/` |
| `omnibrowserPackage` | For release, minified, zipped for each browser's store, with no source map, so that it builds the same wherever it is built; it fails if Firefox Add-ons would reject a file as too large (5 MiB) | `target/**/omnibrowser/release/` |

| Setting | Default | Is |
| --- | --- | --- |
| `omnibrowserTargets` | `Target.Chromium`, `Target.Firefox`, `Target.Safari` | The browsers to build for |
| `omnibrowserExtension` | The only object extending `BrowserExtension` | The object defining the extension, as `Some("com.example.WordCount")` |
| `omnibrowserAssets` | `src/main/extension` | The files copied into the extension as they are, such as its icons |
| `omnibrowserGeckoId` | `<normalizedName>@<organization>` | The extension's ID in Firefox |
| `omnibrowserVersion` | The plugin's own | The version of the Omnibrowser libraries to use with it |

| Target | Runs in | Loads unpacked | Is published to |
| --- | --- | --- | --- |
| `Target.Chromium` | Chrome, Edge, Opera, Brave, Vivaldi and the other browsers built on Chromium | `chrome://extensions`, **Load unpacked** | The Chrome Web Store and Edge Add-ons, from the same zip |
| `Target.Firefox` | Firefox, on desktop and Android | `about:debugging`, **Load Temporary Add-on…** | Firefox Add-ons |
| `Target.Safari` | Safari, on macOS, iOS and iPadOS | Safari on a Mac, **Settings → Developer → Add Temporary Extension…** | The App Store, as an app, from App Store Connect's Safari Web Extension Packager or from Xcode |

Each zip is named `<name>-<version>-<target>.zip`, as `word-count-1.0.0-chromium.zip`.

## ⚙️ How it works

It links the extension's code twice: a bundle for the background and pages, and one for the content scripts, holding only what they reach.
For release, it minifies both with [esbuild](https://esbuild.github.io), at a fixed version that `npx` fetches from npm, as Scala.js advises: its own minifier shortens only the names of properties.
It then runs the first under Node, which gives the extension's manifest, a small bootstrap for each part, and its pages, and writes them out for each browser.
It builds the extension before each test run, so that the tests may run it as built.
As sbt runs it, it is compiled with sbt's own Scala version.
