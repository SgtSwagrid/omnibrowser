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

Add the plugin, which builds the extension, to `project/plugins.sbt`:

```scala
addSbtPlugin("com.alecdorrington" % "sbt-omnibrowser" % "0.1.0")
```

Then enable it, and add the library, in your `build.sbt`:

```scala
lazy val extension = project
  .enablePlugins(OmnibrowserPlugin)
  .settings(
    libraryDependencies += "com.alecdorrington" %% "omnibrowser-core" % "0.1.0",
  )
```

## 🚀 Usage

An extension is an object extending `BrowserExtension`, whose type names the permissions it requests:

```scala
object WordCount extends BrowserExtension[Permission.Storage]:

  override val name = "Word Count"

  val count = Endpoint[String, Int]("count")
  val total = StorageKey.local[Int]("total", 0)

  override val background = Background(
    serve(count): (text, browser) =>
      val words = text.split("\\s+").count(_.nonEmpty)
      browser.storage.update(total)(_ + words).as(words),
  )

  override val contentScripts = Seq(
    ContentScript("https://*/*")(browser =>
      browser.runtime.call(count)(document.body.innerText).void,
    ),
  )
```

`sbt omnibrowserBuild` lays it out for Chrome and Firefox, ready to load unpacked, and `sbt omnibrowserPackage` zips a release for each browser's store.
See [the example](example/src/main/scala/WordCount.scala) for the whole of this extension.

## 👁️ See also

- This library was made using [Scala Library Template](https://github.com/SgtSwagrid/scala-library-template).
