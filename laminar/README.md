# 🌿 Omnibrowser Laminar

`omnibrowser-laminar` writes an extension's pages, and the elements that its content scripts add to pages, with [Laminar](https://laminar.dev).

## ⬇️ Installation

It is published apart from the core library, which is all that the [plugin](../sbt-omnibrowser) adds, so add it yourself:

```scala
libraryDependencies += "com.alecdorrington" %% "omnibrowser-laminar" % omnibrowserVersion.value
```

## 🚀 Usage

```scala
override def popup() = Some(LaminarPage(browser =>
  div(
    p(child.text <-- browser.signal(total).map(sum => s"$sum words counted.")),
    button("Reset", onClick --> browser.observer(_ => browser.storage.set(total, 0))),
  ),
))
```

| | Is | Worth knowing |
| --- | --- | --- |
| `LaminarPage(browser => element)` | A `Page` that renders the element at the end of its body | |
| `LaminarContentScript(matches*)(browser => element)` | A `ContentScript` that renders the element at the end of each page that it matches | |
| `browser.signal(key)` | A `Signal` of a stored value | It is the key's default until the value has been read. |
| `browser.signal(value, tab)` | A `Signal` of a tab's value | Only pages and the background have it. It follows the tab as `browser.tabs.values` does. |
| `browser.writer(key)` | An `Observer` that stores each value | |
| `browser.updater(key)((value, event: E) => ...)` | An `Observer` that transforms the stored value with each event, as Airstream's `Var.updater` does | Scala cannot infer the event's type from `-->`, so name it. |
| `browser.events(program)` | An `EventStream` of the result of an `IO` | |
| `browser.events(stream)` | An `EventStream` of each value of an fs2 `Stream` | The stream runs while anything observes the event stream. |
| `browser.observer(event => program)` | An `Observer` that runs a program with each event | It runs the program at once, as `browser.run` does, so that a request for permissions still sees the user's input. |
