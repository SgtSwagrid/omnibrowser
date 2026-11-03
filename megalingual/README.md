# 🗣️ Omnibrowser Megalingual

`omnibrowser-megalingual` gives an extension what only an extension can ask the browser of languages, as [Megalingual](https://github.com/SgtSwagrid/megalingual)'s `Language`s: the languages that text is written in.

## ⬇️ Installation

It is published apart from the core library, which is all that the [plugin](../sbt-omnibrowser) adds, so add it yourself:

```scala
libraryDependencies += "com.alecdorrington" %% "omnibrowser-megalingual" % omnibrowserVersion.value
```

## 🚀 Usage

```scala
import com.alecdorrington.omnibrowser.megalingual.*

override def contentScripts() = Seq(ContentScript("https://*/*")(browser =>
  browser.languages.detect(document.body.innerText).flatMap(found =>
    IO(println(found.language.fold("Not found")(_.name(in = Language.English)))),
  ),
))
```

| `browser.` | Is | Background | Pages | Content scripts | Worth knowing |
| --- | --- | :-: | :-: | :-: | --- |
| `languages.detect(text)` | The languages that the text is written in, each a code and its share, the `language` of most of it, if found reliably | ✅ | ✅ | ✅ | The browser's own detector, on the device, which only an extension may use. Safari has none, so it finds none. |

The browser's voices, which say text aloud, are Megalingual's own: its `megalingual-web` module's `Speech`, which a web page may use as well. The testkit's `FakeBrowser` detects languages as `detectLanguages` tells it, and has the voices that `listVoices` gives it, for `Speech` and anything else that reads the global `speechSynthesis`, recording what it says in `said`.
