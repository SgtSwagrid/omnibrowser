# 🌿 Omnibrowser Laminar

`omnibrowser-laminar` writes an extension's pages, and the elements that its content scripts add to pages, with [Laminar](https://laminar.dev).

- `LaminarPage` and `LaminarContentScript` render an element at the end of their page's body.
- Extension methods on the browser connect the extension to Airstream: `signal`, `writer` and `updater` for stored values, `signal` for a value that a tab shares, `events` for an `IO` or an fs2 `Stream`, and `observer` to run a program with each event.

Unlike the core library and the testkit, the [plugin](../sbt-omnibrowser) doesn't add it, so add it yourself:

```scala
libraryDependencies += "com.alecdorrington" %% "omnibrowser-laminar" % omnibrowserVersion.value
```

See [Laminar](../README.md#-laminar) in the main README.
