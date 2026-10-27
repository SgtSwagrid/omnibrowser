# 🔁 Relay

An extension that tests how content scripts share values (`Shared.tab`) and serve messages (`Message.tab`) for the rest of an extension.
A content script on each secure page shares a count when asked, until asked to stop, and on some pages another shares one from the start, so that the count passes between them.
The popup records each count of the active tab that it sees.

Its tests run it within the test and as the [plugin](../sbt-omnibrowser) built it for each browser, as only then does each content script that matches a page load its own copy of the content bundle.
It isn't published.
