# 🌿 Omnibrowser Laminar

`omnibrowser-laminar` writes an extension's pages, and the elements that its content scripts add to pages, with [Laminar](https://laminar.dev).

- `LaminarPage` and `LaminarContentScript` render an element at the end of their page's body.
- Extension methods on the browser connect the extension to Airstream: `signal`, `writer` and `updater` for stored values, `signal` for a value that a tab shares, `events` for an `IO` or an fs2 `Stream`, and `observer` to run a program with each event.

It is published apart from the core library, which is all that the [plugin](../sbt-omnibrowser) adds, so add it yourself:

```scala
libraryDependencies += "com.alecdorrington" %% "omnibrowser-laminar" % omnibrowserVersion.value
```

| Binding | Worth knowing |
| --- | --- |
| `signal(key)` | It is the key's default until the value has been read. |
| `signal(value, tab)` | Only pages and the background have it. It follows the tab as `browser.tabs.values` does. |
| `updater(key)((value, event: E) => ...)` | It transforms the stored value with each event, as Airstream's `Var.updater` does. Scala cannot infer the event's type from `-->`, so name it. |
| `events(stream)` | The fs2 `Stream` runs while anything observes the event stream. |
| `observer(event => program)` | It runs the program at once, as `browser.run` does, so that a request for permissions still sees the user's input. |

See [Laminar](../README.md#-laminar) in the main README.
