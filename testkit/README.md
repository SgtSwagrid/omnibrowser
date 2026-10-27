# 🧪 Omnibrowser Testkit

`omnibrowser-testkit` tests an extension without a browser. `FakeBrowser` is an in-memory fake of the WebExtensions API, into which a test installs the extension, acts as the user would, and checks what the extension did.

- `FakeBrowser.install` runs the extension's code within the test.
- `FakeBrowser.installBuilt` runs the extension as the [plugin](../sbt-omnibrowser) built it for a browser, with each part in a sandbox of its own.

Each action waits until the extension has finished responding to it.
Pages have a DOM if the tests can load [jsdom](https://github.com/jsdom/jsdom) from `node_modules`.

The plugin adds it to an extension's tests. See [Testing](../README.md#-testing) in the main README.
