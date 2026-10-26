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
addSbtPlugin("com.alecdorrington" % "sbt-omnibrowser" % "0.3.0")
```

Then enable it in your `build.sbt`:

```scala
lazy val extension = project.enablePlugins(OmnibrowserPlugin)
```

The plugin adds the library, `omnibrowser-core`, and the testkit for your tests, `omnibrowser-testkit`, at its own version, which `omnibrowserVersion` holds.

## 🚀 Usage

An extension is an object extending `BrowserExtension`, whose type names the permissions it requests:

```scala
object WordCount extends BrowserExtension[Permission.Storage]:

  override val name = "Word Count"

  val count = Endpoint[String, Int]("count")
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

Each part of an extension, `background()`, `contentScripts()`, `popup()` and `options()`, is a method, so that each is built only where it runs.
The content scripts load a bundle of their own, which holds only what `contentScripts()` and the object's own values reach: keep anything that only the background or a page needs within its method, or in an object of its own, rather than in a value of the extension object.

A content script's stylesheets come with it, as in `ContentScript(...)(...).withCss("content.css")`, and a page may begin with a body and stylesheets of its own, as in `Page(...).withBody("<main></main>").withStylesheets("popup.css")`, each a path among the assets in `src/main/extension`.

The pages that an extension may access, such as the APIs that its background calls, are its `hosts`. It may also ask the user for `optionalPermissions` and `optionalHosts` while it runs, through `browser.grants`, which gives evidence of a permission once the user grants it:

```scala
browser.grants.request[Permission.Tabs].flatMap:
  case Some(granted) => ... // `given Granted[Permission.Tabs] = granted` unlocks the APIs it covers.
  case None          => ... // The user refused.
```

A handler that needs to know who made a request takes its sender too, as in `serve(endpoint)((request, sender, browser) => ...)`, where `sender.tab` is the tab of the content script that asked, if one did.

Firefox for Android may install the extension unless it declares `android = Android.Unsupported`, and `Android.Supported(Some("120.0"))` sets the earliest version. The build warns of each permission that the extension asks for and Firefox for Android lacks.

The browser asks the user only in response to their input, so a page's DOM event handler starts such a program with `browser.run(program)`, which runs it at once.

Messages and stored values hold JSON, through a `Codec`: there are codecs of the usual types, and `derives Codec` makes one of a case class, enum or sealed trait.

Firefox shows the user what personal data an extension collects, and its store requires every new extension to say: override `dataCollection` if yours collects any, as it otherwise declares none.

`sbt omnibrowserBuild` lays it out for Chrome and Firefox, ready to load unpacked, and `sbt omnibrowserPackage` zips a release for each browser's store.
A release holds no source map, so that it builds the same wherever it is built, as stores that rebuild it from its source expect.
See [the example](example/src/main/scala/WordCount.scala) for the whole of this extension.

## 🧪 Testing

`FakeBrowser`, from the testkit, is a browser that keeps everything in memory.
A test installs the extension into it, acts as the user would, and checks what the extension did:

```scala
test("A page's words are added to the total."):
  for
    browser <- FakeBrowser.install(WordCount)
    _       <- browser.visit("https://example.com/", "The quick brown fox")
    total   <- browser.stored(WordCount.total)
    badge   <- browser.badge
  yield
    assertEquals(total, 4)
    assertEquals(badge, "4")
```

Each action (`visit`, `openPopup`, `clickAction`, `call`, `store`, ...) waits until the extension has finished responding to it, and fails if the extension failed.
`FakeBrowser.install` runs the extension's code within the test, where a page's document is a stand-in with only a body of text.
`FakeBrowser.installBuilt(Target.Chrome)` instead runs the extension as the plugin built it for that browser, with each part in a sandbox of its own; the plugin builds it before the tests run.

## 👁️ See also

- This library was made using [Scala Library Template](https://github.com/SgtSwagrid/scala-library-template).
