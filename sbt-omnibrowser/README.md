# 🔧 sbt-omnibrowser

The sbt plugin that builds an extension written with Omnibrowser, for Chrome and Firefox.

## ⬇️ Installation

```scala
// project/plugins.sbt
addSbtPlugin("com.alecdorrington" % "sbt-omnibrowser" % "0.5.0")

// build.sbt
lazy val extension = project.enablePlugins(OmnibrowserPlugin)
```

It adds only [`omnibrowser-core`](../core), whose entry points it builds the extension from, to the project at `omnibrowserVersion`.
The [testkit](../testkit) and [`omnibrowser-laminar`](../laminar) are the project's to add, at the same version.

## 🚀 Usage

| Task | Builds the extension | Into |
| --- | --- | --- |
| `omnibrowserBuild` | For development, unpacked for each browser, ready to load | `target/**/omnibrowser/development/<browser>/` |
| `omnibrowserPackage` | For release, zipped for each browser's store, with no source map, so that it builds the same wherever it is built | `target/**/omnibrowser/release/` |

| Setting | Default | Is |
| --- | --- | --- |
| `omnibrowserTargets` | `Target.Chrome`, `Target.Firefox` | The browsers to build for |
| `omnibrowserExtension` | The only object extending `BrowserExtension` | The object defining the extension, as `Some("com.example.WordCount")` |
| `omnibrowserAssets` | `src/main/extension` | The files copied into the extension as they are, such as its icons |
| `omnibrowserGeckoId` | `<normalizedName>@<organization>` | The extension's ID in Firefox |
| `omnibrowserVersion` | The plugin's own | The version of the Omnibrowser libraries to use with it |

## ⚙️ How it works

It links the extension's code twice: a bundle for the background and pages, and one for the content scripts, holding only what they reach.
It then runs the first under Node, which gives the extension's manifest, a small bootstrap for each part, and its pages, and writes them out for each browser.
It builds the extension before each test run, so that the tests may run it as built.
As sbt runs it, it is compiled with sbt's own Scala version.
