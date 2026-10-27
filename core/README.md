# 🧩 Omnibrowser Core

`omnibrowser-core` is the library itself, for Scala.js: a typed model of the browser to write an extension against, and its implementation over the [WebExtensions API](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions).

- The model: an extension (`BrowserExtension`) and its parts, the `Browser` that each part sees, the messages between them (`Message`), the values that content scripts share (`Shared`), stored values (`StorageKey`), their JSON (`Codec`), and the `Permission`s that the extension asks for, in its type.
- `webext`: the model over the browser's own API (`WebExtensionBrowser`).
- `launch`: the entry points of the extension's bundles, which start each part where it runs, and give the [plugin](../sbt-omnibrowser) the extension's manifest, bootstraps and pages for each browser.

| Where | Worth knowing |
| --- | --- |
| `serve` | `serve(message)((request, sender, browser) => ...)` also learns who asked, as `sender.tab`. |
| `serve`, `share` in a content script | Each is a `Resource`, in use while the script serves or shares. A request of a tab where nothing serves it fails. |
| `share` | If several content scripts of a page share a value, the one that began last owns it, until it stops. |
| `tabs.values` | It follows its tab to each page it loads, even one whose content script starts late or returns from the back-forward cache, and ends when the tab closes. |
| `tabs.values` in the background | Chrome may stop a background that only watches, so watch from a page, such as the popup. |
| `tabs.values` in Firefox | Firefox may keep a port to a page in its back-forward cache open, so a watcher sees that page's last value until it returns. |
| `grants.request` | The browser asks the user only in response to their input, so start it from a DOM event handler with `browser.run`, which runs it at once. |
| `StorageKey.sync` | Firefox for Android keeps its values on that device only. |
| `android` | The build warns of each permission that the extension asks for and Firefox for Android lacks. |
| Names | Messages and values whose names begin `omnibrowser/` are the library's own. |

The plugin adds it to an extension's project. See [the main README](../README.md) for how to use it.
