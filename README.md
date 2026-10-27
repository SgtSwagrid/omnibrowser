<div align="center">

  <h1>🧩 Omnibrowser</h1>
  <p>Write browser extensions in Scala against a typed model of the browser.</p>
  
  <span>
    <a href="https://github.com/SgtSwagrid/omnibrowser/actions/workflows/build-integrity.yml"><img src="https://github.com/SgtSwagrid/omnibrowser/actions/workflows/build-integrity.yml/badge.svg" alt="Build status" /></a>
    <a href="https://search.maven.org/artifact/com.alecdorrington/omnibrowser-core_sjs1_3"><img src="https://img.shields.io/maven-central/v/com.alecdorrington/omnibrowser-core_sjs1_3.svg" alt="Maven Central" /></a>
  </span>
  
</div>

## ⬇️ Installation

```scala
// project/plugins.sbt
addSbtPlugin("com.alecdorrington" % "sbt-omnibrowser" % "0.5.0")

// build.sbt
lazy val extension = project.enablePlugins(OmnibrowserPlugin)
```

| Module | For |
| --- | --- |
| [`omnibrowser-core`](core) | Writing the extension |
| [`sbt-omnibrowser`](sbt-omnibrowser) | Building it for Chrome and Firefox |
| [`omnibrowser-testkit`](testkit) | Testing it in a fake browser |
| [`omnibrowser-laminar`](laminar) | Writing its pages with [Laminar](https://laminar.dev) |

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

See [the example](example/src/main/scala/WordCount.scala) for its popup and tests.

### 🧱 Parts

| Part | Method | Made with |
| --- | --- | --- |
| Background | `background()` | `Background(onInstalled(...), onStartup(...), onActionClicked(...), serve(...))` |
| Content scripts | `contentScripts()` | `ContentScript(matches*)(run)`, `.withCss(...)`, `.at(RunAt.Start)` |
| Popup | `popup()` | `Some(Page(run))`, `.withBody(html)`, `.withStylesheets(...)` |
| Options page | `options()` | `Some(Page(run))` |

Content scripts get a bundle of their own, holding only what `contentScripts()` and the object's own values reach, so keep what the other parts need within their methods.

| `browser.` | Background | Pages | Content scripts |
| --- | :-: | :-: | :-: |
| `storage` | ✅ | ✅ | ✅ |
| `tabs`: `all`, `active`, `open` | ✅ | ✅ | |
| `tabs`: `call`, `get`, `values` | ✅ | ✅ | |
| `action`: the toolbar badge | ✅ | ✅ | |
| `grants`: permissions at runtime | ✅ | ✅ | |
| `serve`, `share`: for its tab | | | ✅ |

### 🔀 Passing values

| | Declared as | Given by | Taken by |
| --- | --- | --- | --- |
| Request | `Message[Req, Res](name)` | The background: `serve` | Pages and content scripts: `runtime.call` |
| Request of a tab | `Message.tab[Req, Res](name)` | Its content script: `serve` | The background and pages: `tabs.call` |
| Value of a tab | `Shared.tab[A](name, default)` | Its content script: `share`, from an fs2 `Signal` | The background and pages: `tabs.get`, `tabs.values` |
| Stored value | `StorageKey.local[A](name, default)` | Any part: `storage.set` | Any part: `storage.get`, `storage.values` |

Values are JSON, through a `Codec`, which `derives Codec` makes for a case class, enum or sealed trait.
`tabs.values` follows its tab from page to page, giving the default where nothing shares the value.

| Storage area | Keeps a value | Content scripts see it |
| --- | --- | :-: |
| `StorageKey.local` | On this device | ✅ |
| `StorageKey.sync` | On each device the user signs in to | ✅ |
| `StorageKey.session` | Until the browser closes | |

### 🔐 Permissions

| Permission | Unlocks |
| --- | --- |
| `Permission.Storage` | `browser.storage` |
| `Permission.Tabs` | Every tab's URL and title |
| `Permission.ActiveTab` | The active tab's page, when the user invokes the extension |

| Member | Declares |
| --- | --- |
| `BrowserExtension[P]` | The permissions `P`, as `Permission.Storage & Permission.Tabs` |
| `hosts` | The pages it may access, such as APIs |
| `optionalPermissions`, `optionalHosts` | What `browser.grants.request` may ask for, from a user's input |
| `dataCollection` | The personal data it collects, for Firefox |
| `android` | `Android.Unsupported`, or the earliest version of Firefox for Android |

## 🌿 Laminar

```scala
// build.sbt
libraryDependencies += "com.alecdorrington" %% "omnibrowser-laminar" % omnibrowserVersion.value
```

```scala
override def popup() = Some(LaminarPage(browser =>
  div(
    p(child.text <-- browser.signal(total).map(sum => s"$sum words counted.")),
    button("Reset", onClick --> browser.observer(_ => browser.storage.set(total, 0))),
  ),
))
```

| | Is |
| --- | --- |
| `LaminarPage(...)`, `LaminarContentScript(matches*)(...)` | A page or content script that renders an element |
| `browser.signal(key)`, `browser.signal(value, tab)` | A `Signal` of a stored value, or of a tab's value |
| `browser.writer(key)`, `browser.updater(key)(...)` | An `Observer` that stores, or transforms, a value |
| `browser.events(program)`, `browser.events(stream)` | An `EventStream` of an `IO`, or an fs2 `Stream` |
| `browser.observer(event => program)` | An `Observer` that runs a program at once |

## 🧪 Testing

```scala
// build.sbt
libraryDependencies += "com.alecdorrington" %% "omnibrowser-testkit" % omnibrowserVersion.value % Test
```

```scala
test("A page's words are added to the total."):
  for
    browser <- FakeBrowser.install(WordCount)
    _       <- browser.visit("https://example.com/", "The quick brown fox")
    total   <- browser.stored(WordCount.total)
  yield assertEquals(total, 4)
```

| | Runs |
| --- | --- |
| `FakeBrowser.install(extension)` | The extension's code, within the test |
| `FakeBrowser.installBuilt(Target.Chrome)` | The extension as the plugin built it, each part in a sandbox |

Pages have a DOM with [jsdom](https://github.com/jsdom/jsdom) (`npm install --save-dev jsdom`). See [the testkit](testkit) for every action.

## 📦 Building

| Command | Builds |
| --- | --- |
| `sbt omnibrowserBuild` | For development, unpacked |
| `sbt omnibrowserPackage` | For release, zipped for each store |

Files in `src/main/extension` are copied in as they are. See [the plugin](sbt-omnibrowser) for its settings.

## 👁️ See also

- This library was made using [Scala Library Template](https://github.com/SgtSwagrid/scala-library-template).
