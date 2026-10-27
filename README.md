<div align="center">

  <h1>🧩 Omnibrowser</h1>
  <p>Write browser extensions in Scala against a typed model of the browser.</p>
  
  <span>
    <a href="https://github.com/SgtSwagrid/omnibrowser/actions/workflows/build-integrity.yml"><img src="https://github.com/SgtSwagrid/omnibrowser/actions/workflows/build-integrity.yml/badge.svg" alt="Build status" /></a>
    <a href="https://search.maven.org/artifact/com.alecdorrington/omnibrowser-core_sjs1_3"><img src="https://img.shields.io/maven-central/v/com.alecdorrington/omnibrowser-core_sjs1_3.svg" alt="Maven Central" /></a>
  </span>
  
</div>

## ⬇️ Installation

Add the plugin, which builds the extension, to `project/plugins.sbt`:

```scala
addSbtPlugin("com.alecdorrington" % "sbt-omnibrowser" % "0.5.0")
```

Then enable it in your `build.sbt`:

```scala
lazy val extension = project.enablePlugins(OmnibrowserPlugin)
```

The plugin adds the library, `omnibrowser-core`, and the testkit for your tests, `omnibrowser-testkit`, at its own version, which `omnibrowserVersion` holds.
To write the extension's pages with [Laminar](https://laminar.dev), add `omnibrowser-laminar` too:

```scala
libraryDependencies += "com.alecdorrington" %% "omnibrowser-laminar" % omnibrowserVersion.value
```

## 🚀 Usage

An extension is an object extending `BrowserExtension`, whose type names the permissions it requests:

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

Each part of an extension, `background()`, `contentScripts()`, `popup()` and `options()`, is a method, so that each is built only where it runs.
The content scripts load a bundle of their own, which holds only what `contentScripts()` and the object's own values reach: keep anything that only the background or a page needs within its method, or in an object of its own, rather than in a value of the extension object.

A content script's stylesheets come with it, as in `ContentScript(...)(...).withCss("content.css")`, and a page may begin with a body and stylesheets of its own, as in `Page(...).withBody("<main></main>").withStylesheets("popup.css")`, each a path among the assets in `src/main/extension`.

The pages that an extension may access, such as the APIs that its background calls, are its `hosts`. It may also ask the user for `optionalPermissions` and `optionalHosts` while it runs, through `browser.grants`, which gives evidence of a permission once the user grants it:

```scala
browser.grants.request[Permission.Tabs].flatMap:
  case Some(granted) => ... // `given Granted[Permission.Tabs] = granted` unlocks the APIs it covers.
  case None          => ... // The user refused.
```

A handler that needs to know who made a request takes its sender too, as in `serve(message)((request, sender, browser) => ...)`, where `sender.tab` is the tab of the content script that asked, if one did.

The background and pages may make requests of a tab's content scripts too, and read the values that they share, so that what a content script knows reaches the popup without passing through storage.
A content script serves a `Message.tab`, and shares a `Shared.tab` value from an fs2 `Signal`, each while its `Resource` is in use:

```scala
val problem = Shared.tab[String]("problem", "")
val dismiss = Message.tab[Unit, Unit]("dismiss")

// In a content script.
SignallingRef[IO].of("").flatMap(last =>
  (browser.serve(dismiss)(_ => last.set("")) >> browser.share(problem)(last)).surround(...),
)

// In the background or a page.
browser.tabs.call(dismiss, tab)(())
browser.tabs.get(problem, tab)    // Its value now.
browser.tabs.values(problem, tab) // Its value, then each new value.
```

While nothing in a tab shares a value, its value there is its default, as an unset `StorageKey`'s is, and a request of a tab where nothing serves its message fails.
`values` follows the tab: it gives the default before a content script there begins sharing the value, after it stops, and while the tab loads another page, whose content script it then finds, and it ends once the tab closes.
It finds them as a page that begins sharing values tells the rest of the extension, and tells it again on returning from the back-forward cache or on being shown after it was prerendered. Each time reaches every open page of the extension, and wakes the background if it serves any message.
If several content scripts of one page share a value, the one that began last is its owner, until it stops.
Chrome may stop the background while it watches a tab, as an open port does not keep it running, so watch from a page, such as the popup.
Firefox may keep a port to a page in its back-forward cache open, so a watcher there sees that page's last value until it returns.
Names beginning `omnibrowser/` are the library's own.

Firefox for Android may install the extension unless it declares `android = Android.Unsupported`, and `Android.Supported(Some("120.0"))` sets the earliest version. The build warns of each permission that the extension asks for and Firefox for Android lacks.

The browser asks the user only in response to their input, so a page's DOM event handler starts such a program with `browser.run(program)`, which runs it at once.

Messages and stored values hold JSON, through a `Codec`: there are codecs of the usual types, and `derives Codec` makes one of a case class, enum or sealed trait.

Firefox shows the user what personal data an extension collects, and its store requires every new extension to say: override `dataCollection` if yours collects any, as it otherwise declares none.

`sbt omnibrowserBuild` lays it out for Chrome and Firefox, ready to load unpacked, and `sbt omnibrowserPackage` zips a release for each browser's store.
A release holds no source map, so that it builds the same wherever it is built, as stores that rebuild it from its source expect.
See [the example](example/src/main/scala/WordCount.scala) for the whole of this extension.

## 🌿 Laminar

With `omnibrowser-laminar`, [Laminar](https://laminar.dev) renders a page, or the element that a content script adds to its page:

```scala
import com.alecdorrington.omnibrowser.laminar.*
import com.raquo.laminar.api.L.*

override def popup() = Some(LaminarPage(browser =>
  div(
    p(child.text <-- browser.signal(total).map(sum => s"$sum words counted.")),
    button("Reset", onClick --> browser.observer(_ => browser.runtime.call(reset)(()))),
  ),
))
```

`LaminarPage(...)` renders its element at the end of the page's body, and `LaminarContentScript("https://*/*")(...)` at the end of the body of each page that it matches.
The browser connects Laminar to the extension:

- `browser.signal(key)` is the stored value of a `StorageKey`, which is its default until it has been read, and `browser.writer(key)` stores each value that it observes, as in `onInput.mapToChecked --> browser.writer(enabled)`, while `browser.updater(key)((count, _) => count + 1)` transforms the stored value with each event, as Airstream's `Var.updater` does.
  A transform that uses what the event carries names its type, as in `browser.updater(tags)((all, tag: String) => tag :: all)`, since Scala cannot infer it.
- `browser.events(program)` is the result of an `IO`, and `browser.events(stream)` each value of an fs2 `Stream`, which runs while anything observes the event stream.
- `browser.observer(event => program)` runs a program with each event, at once, as `browser.run` does, so that a request for permissions still sees the user's input.
- In a page, `browser.signal(shared, tab)` is the value of a `Shared.tab` in a tab, which follows it as `browser.tabs.values` does, as in `browser.events(browser.tabs.active).collect { case Some(tab) => tab.id }.flatMapSwitch(browser.signal(problem, _))`.

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
A test may also act as the background would, as in `browser.call(message, page.tab.get)(request)` and `browser.shared(value, page.tab.get)`, and close a tab or have it load another page, as in `browser.close(page.tab.get)` and `browser.navigate(page.tab.get, url)`.
`FakeBrowser.install` runs the extension's code within the test.

Each page has a DOM if the tests can load [jsdom](https://github.com/jsdom/jsdom), which `npm install --save-dev jsdom` installs into the project's `node_modules`, and Laminar's pages need one.
A test may then act on the page's elements as the user would, as in `popup.click("button")`, `popup.enter("input", "Ada")` and `popup.text("p")`.
Without jsdom, a page's document is a stand-in with only a body of text.
`FakeBrowser.installBuilt(Target.Chrome)` instead runs the extension as the plugin built it for that browser, with each part in a sandbox of its own, which for a page, given jsdom, is a window made from the HTML that the plugin wrote; the plugin builds it before the tests run.

## 👁️ See also

- This library was made using [Scala Library Template](https://github.com/SgtSwagrid/scala-library-template).
