# 🧩 Omnibrowser Core

`omnibrowser-core` is the library itself, for Scala.js: a typed model of the browser to write an extension against, and its implementation over the [WebExtensions API](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions).

## ⬇️ Installation

The [plugin](../sbt-omnibrowser) adds it to an extension's project. To use it without the plugin:

```scala
libraryDependencies += "com.alecdorrington" %% "omnibrowser-core" % "0.5.0"
```

## 🧱 Parts

An extension is an object extending `BrowserExtension`, whose type names the permissions it needs, and whose methods give its parts:

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

## 🔀 Passing values

| | Declared as | Given by | Taken by |
| --- | --- | --- | --- |
| Request | `Message[Req, Res](name)` | The background: `serve` | Pages and content scripts: `runtime.call` |
| Request of a tab | `Message.tab[Req, Res](name)` | Its content script: `serve` | The background and pages: `tabs.call` |
| Value of a tab | `Shared.tab[A](name, default)` | Its content script: `share`, from an fs2 `Signal` | The background and pages: `tabs.get`, `tabs.values` |
| Stored value | `StorageKey.local[A](name, default)` | Any part: `storage.set` | Any part: `storage.get`, `storage.values` |

Values are JSON, through [circe](https://circe.github.io/circe/)'s `Encoder` and `Decoder`, which `derives Codec` makes for a case class, enum or sealed trait.
A `Long` beyond 2<sup>53</sup> loses precision, as JavaScript's numbers hold no more.
A stored value that can't be decoded, as one that an earlier version stored differently, is its key's default until it is set again, and the console warns of it.
To read an old format instead, decode that too, as with circe's `Decoder#or`.
`tabs.values` follows its tab from page to page, giving the default where nothing shares the value.

| Storage area | Keeps a value | Content scripts see it |
| --- | --- | :-: |
| `StorageKey.local` | On this device | ✅ |
| `StorageKey.sync` | On each device the user signs in to | ✅ |
| `StorageKey.session` | Until the browser closes | |

## 🔐 Permissions

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

## ⚠️ Worth knowing

| Where | Worth knowing |
| --- | --- |
| `serve` | `serve(message)((request, sender, browser) => ...)` also learns who asked, as `sender.tab`. |
| `serve`, `share` in a content script | Each is a `Resource`, in use while the script serves or shares. A request of a tab where nothing serves it fails. |
| `share` | If several content scripts of a page share a value, the one that began last owns it, until it stops. |
| `tabs.values` | It follows its tab to each page it loads, even one whose content script starts late or returns from the back-forward cache, and ends when the tab closes. |
| `tabs.values` in the background | Chromium may stop a background that only watches, so watch from a page, such as the popup. |
| `tabs.values` in Firefox | Firefox may keep a port to a page in its back-forward cache open, so a watcher sees that page's last value until it returns. |
| `grants.request` | The browser asks the user only in response to their input, so start it from a DOM event handler with `browser.run`, which runs it at once. |
| `StorageKey.sync` | Firefox for Android and Safari keep its values on that device only. |
| `contentScripts()` in Safari | Safari runs them on a site only once the user grants the extension access to it, from its toolbar button. |
| `setBadgeColour` in Safari | Safari keeps its own colour. |
| `options()` in Safari | Safari opens the options page in a tab of its own. |
| `android` | The build warns of each permission that the extension asks for and Firefox for Android lacks. |
| Names | Messages and values whose names begin `omnibrowser/` are the library's own. |

## 🗂️ Packages

| Package | Holds |
| --- | --- |
| `com.alecdorrington.omnibrowser` | The model: `BrowserExtension` and its parts, the `Browser` each part sees, `Message`, `Shared`, `StorageKey` and `Permission` |
| `com.alecdorrington.omnibrowser.webext` | The model over the browser's own API (`WebExtensionBrowser`) |
| `com.alecdorrington.omnibrowser.launch` | The entry points of the extension's bundles, which start each part where it runs, and give the [plugin](../sbt-omnibrowser) the manifest, bootstraps and pages for each browser |
