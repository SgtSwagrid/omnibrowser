# 🧪 Omnibrowser Testkit

`omnibrowser-testkit` tests an extension without a browser. `FakeBrowser` is an in-memory fake of the WebExtensions API, into which a test installs the extension, acts as the user would, and checks what the extension did.

## ⬇️ Installation

It is published apart from the core library, which is all that the [plugin](../sbt-omnibrowser) adds, so add it to your tests yourself:

```scala
libraryDependencies += "com.alecdorrington" %% "omnibrowser-testkit" % omnibrowserVersion.value % Test
```

Pages have a DOM if the tests can load [jsdom](https://github.com/jsdom/jsdom), which `npm install --save-dev jsdom` installs into the project's `node_modules`.
Without it, a page is a stand-in with only a body of text, so [Laminar's](../laminar) pages need it.

## 🚀 Usage

```scala
test("A page's words are added to the total."):
  for
    browser <- FakeBrowser.install(WordCount)
    _       <- browser.visit("https://example.com/", "The quick brown fox")
    total   <- browser.stored(WordCount.total)
  yield assertEquals(total, 4)
```

| | Runs | Each page, given jsdom |
| --- | --- | --- |
| `FakeBrowser.install(extension)` | The extension's code, within the test | A document of one shared window |
| `FakeBrowser.installBuilt(Target.Chrome)` | The extension as the [plugin](../sbt-omnibrowser) built it for that browser, each part in a sandbox of its own | A window of its own, from the HTML that the plugin wrote |

| Acting as | `browser.` |
| --- | --- |
| The user | `visit(url, text)`, `navigate(tab, url, text)`, `close(tab)`, `openPopup`, `openOptions`, `clickAction`, `answerRequests(grant)` |
| The user, on a page that `visit` or `openPopup` gave | `click(selector)`, `enter(selector, text)`, `text(selector)`, `value(selector)`, `isChecked(selector)` |
| The browser | `restart` |
| A content script | `call(message)(request)` |
| The background | `call(message, tab)(request)`, `shared(value, tab)` |
| Another device | `store(key, value)` |
| A check | `stored(key)`, `badge`, `badgeOf(tab)`, `badgeColour`, `badgeColourOf(tab)`, `tabs` |

Each action waits until the extension has finished responding to it, and fails if the extension failed.
