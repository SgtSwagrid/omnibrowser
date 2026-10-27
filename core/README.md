# 🧩 Omnibrowser Core

`omnibrowser-core` is the library itself, for Scala.js: a typed model of the browser to write an extension against, and its implementation over the [WebExtensions API](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions).

- The model: an extension (`BrowserExtension`) and its parts, the `Browser` that each part sees, the messages between them (`Message`), the values that content scripts share (`Shared`), stored values (`StorageKey`), their JSON (`Codec`), and the `Permission`s that the extension asks for, in its type.
- `webext`: the model over the browser's own API (`WebExtensionBrowser`).
- `launch`: the entry points of the extension's bundles, which start each part where it runs, and give the [plugin](../sbt-omnibrowser) the extension's manifest, bootstraps and pages for each browser.

The plugin adds it to an extension's project. See [the main README](../README.md) for how to use it.
