# 🗣️ Omnibrowser Megalingual

`omnibrowser-megalingual` gives an extension what the browser knows of languages, as [Megalingual](https://github.com/SgtSwagrid/megalingual)'s `Language`s: the languages that text is written in, and voices that say text aloud.

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
    found.language.fold(IO.unit)(language =>
      browser.speech.voice(language).flatMap(_.fold(IO.unit)(voice =>
        browser.speech.say(document.title, voice),
      )),
    ),
  ),
))
```

| `browser.` | Is | Background | Pages | Content scripts | Worth knowing |
| --- | --- | :-: | :-: | :-: | --- |
| `languages.detect(text)` | The languages that the text is written in, each a code and its share, the `language` of most of it, if found reliably | ✅ | ✅ | ✅ | The browser's own detector, on the device. Safari has none, so it finds none. |
| `speech.voices` | The browser's voices, each with its `language` and whether it is `local` | Firefox | ✅ | ✅ | It waits a moment for Chromium, which lists none until first asked. A service worker, as Chromium's and Safari's background is, has none. |
| `speech.voice(language)` | The voice that best says the language's words | Firefox | ✅ | ✅ | The nearest in Megalingual's tree to the variety it is spoken in (German in German German), one on the device before one online. None where there is none of the language's tree. |
| `speech.say(text, voice)` | Says the text aloud, done once it is said | Firefox | ✅ | ✅ | It cuts short whatever the browser was saying, the page's own speech too. It begins in its first step, so that `browser.run` from a click still has the user's activation. An online voice sends the text to the browser's maker. |

The testkit's `FakeBrowser` detects languages as `detectLanguages` tells it, and has the voices that `listVoices` gives it, recording what it says in `said`.
