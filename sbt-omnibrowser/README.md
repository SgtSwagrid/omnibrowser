# 🔧 sbt-omnibrowser

The sbt plugin that builds an extension written with Omnibrowser, for Chrome and Firefox.

It links the extension's code twice: a bundle for the background and pages, and one for the content scripts, holding only what they reach.
It then runs the first under Node, which gives the extension's manifest, a small bootstrap for each part, and its pages, and writes them out for each browser.

- `omnibrowserBuild` lays the extension out for each browser, ready to load unpacked.
- `omnibrowserPackage` zips a release for each browser's store.

It adds [`omnibrowser-core`](../core) and [`omnibrowser-testkit`](../testkit) to the project at its own version (`omnibrowserVersion`), and builds the extension before each test run, so that the tests may run it as built.
As sbt runs it, it is compiled with sbt's own Scala version.

See [the main README](../README.md) for how to install it.
