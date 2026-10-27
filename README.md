<div align="center">

  <h1>🧩 Omnibrowser</h1>
  <p>Write browser extensions in Scala against a typed model of the browser.</p>
  
  <span>
    <a href="https://github.com/SgtSwagrid/omnibrowser/actions/workflows/build-integrity.yml"><img src="https://github.com/SgtSwagrid/omnibrowser/actions/workflows/build-integrity.yml/badge.svg" alt="Build status" /></a>
    <a href="https://search.maven.org/artifact/com.alecdorrington/omnibrowser-core_sjs1_3"><img src="https://img.shields.io/maven-central/v/com.alecdorrington/omnibrowser-core_sjs1_3.svg" alt="Maven Central" /></a>
    <a href="https://alecdorrington.com/omnibrowser"><img src="https://img.shields.io/badge/docs-latest-blue.svg" alt="Documentation" /></a>
  </span>
  
</div>

## ⬇️ Installation

```scala
// project/plugins.sbt
addSbtPlugin("com.alecdorrington" % "sbt-omnibrowser" % "0.5.0")

// build.sbt
lazy val extension = project.enablePlugins(OmnibrowserPlugin)
```

## 🚀 Usage

```scala
object WordCount extends BrowserExtension[Permission.Storage]:

  override val name = "Word Count"

  val count = Message[String, Int]("count")
  val total = StorageKey.local[Int]("total", 0)

  override def background() = Background(
    serve(count): (text, browser) =>
      val words = text.split("\\s+").count(_.nonEmpty)
      browser.storage.update(total)(_ + words).as(words),
  )

  override def contentScripts() = Seq(
    ContentScript("https://*/*")(browser =>
      browser.runtime.call(count)(document.body.innerText).void,
    ),
  )
```

`sbt omnibrowserBuild` builds it for Chromium (Chrome, Edge and the rest), Firefox and Safari, ready to load unpacked.

## 📦 Modules

| Module | For |
| --- | --- |
| [`omnibrowser-core`](core) | Writing the extension: its parts, the messages and values passed between them, storage and permissions |
| [`sbt-omnibrowser`](sbt-omnibrowser) | Building it for Chromium, Firefox and Safari |
| [`omnibrowser-testkit`](testkit) | Testing it in a fake browser |
| [`omnibrowser-laminar`](laminar) | Writing its pages with [Laminar](https://laminar.dev) |
| [`example`](example) | An example extension, with a Laminar popup and tests (not published) |

## 👁️ See also

- This library was made using [Scala Library Template](https://github.com/SgtSwagrid/scala-library-template).
