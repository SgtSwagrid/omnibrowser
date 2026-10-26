package com.alecdorrington.omnibrowser.sharing

import com.alecdorrington.omnibrowser.Target
import com.alecdorrington.omnibrowser.testkit.FakeBrowser
import munit.CatsEffectSuite

/**
  * Checks how a page's content scripts share a value, both as their code runs
  * within the test, and as the plugin built them for each browser, where each
  * loads its own copy of the content bundle.
  */
class RelaySuite extends CatsEffectSuite:

  private val installs = Seq("within the test" -> FakeBrowser.install(Relay)) ++
    Target
      .values
      .map(target =>
        s"as built for ${ target.toString }" -> FakeBrowser.installBuilt(target),
      )

  installs.foreach: (name, install) =>

    test(
      s"Of two scripts on a page that share a value, the later's is its value, until it stops, $name.",
    ):
      for
        browser <- install
        page    <- browser.visit("https://example.com/both")
        _       <- browser.openPopup
        _       <- browser.call(Relay.begin, page.tab.get)(())
        shared  <- browser.shared(Relay.count, page.tab.get)
        _       <- browser.call(Relay.stop, page.tab.get)(())
        seen    <- browser.stored(Relay.seen)
      yield
        assertEquals(shared, 7)
        assertEquals(seen, List(1, 7, 1))

    test(s"The popup sees a value that its tab begins sharing late, $name."):
      for
        browser <- install
        page    <- browser.visit("https://example.com/late")
        _       <- browser.openPopup
        _       <- browser.call(Relay.begin, page.tab.get)(())
        seen    <- browser.stored(Relay.seen)
      yield assertEquals(seen, List(7, 0))

    test(s"The popup follows its tab to the next page, $name."):
      for
        browser <- install
        page    <- browser.visit("https://example.com/both")
        _       <- browser.openPopup
        _       <- browser.navigate(
          page.tab.get,
          "https://example.com/late",
        )
        _    <- browser.call(Relay.begin, page.tab.get)(())
        _    <- browser.close(page.tab.get)
        seen <- browser.stored(Relay.seen)
      yield assertEquals(seen, List(0, 7, 0, 1))
