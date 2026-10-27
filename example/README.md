# 📖 Word Count

An example extension, which counts the words on each page that the user visits.
Its popup, written with [Laminar](../laminar), shows how many it has counted in all and on the page in the active tab, and resets the count.

It tests the library, the [plugin](../sbt-omnibrowser) and the [testkit](../testkit) together: its tests run it in the fake browser both within the test and as the plugin built it for each browser.
It isn't published.

`sbt omnibrowser-example/omnibrowserBuild` lays it out for Chromium, Firefox and Safari under `target/out`, ready to load unpacked.
