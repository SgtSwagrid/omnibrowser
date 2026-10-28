package com.alecdorrington.omnibrowser.example

import com.alecdorrington.omnibrowser.Target
import com.alecdorrington.omnibrowser.testkit.FakeBrowser
import munit.CatsEffectSuite

/**
  * Checks what Word Count does, both as its code runs within the test, and as
  * the plugin built it for each browser.
  */
class WordCountBehaviourSuite extends CatsEffectSuite:

  private val installs = Seq(
    "within the test" -> FakeBrowser.install(WordCount),
  ) ++
    Target
      .values
      .map(target =>
        s"as built for ${ target.toString }" -> FakeBrowser.installBuilt(target),
      )

  installs.foreach: (name, install) =>
    test(
      s"Each secure page's words are added to the total, which the badge and popup show beside the active tab's own, until the popup's button resets it, $name.",
    ):
      for
        browser <- install
        _       <- browser.visit(
          "https://example.com/",
          "The quick brown fox",
        )
        _        <- browser.visit("http://example.org/", "Not counted")
        insecure <- browser.openPopup.flatMap(_.text(".page"))
        _        <- browser.visit(
          "https://example.net/",
          "jumps over\n the lazy dog",
        )
        total   <- browser.stored(WordCount.total)
        badge   <- browser.badge
        popup   <- browser.openPopup
        text    <- popup.text("p")
        page    <- popup.text(".page")
        _       <- popup.click("button")
        reset   <- browser.stored(WordCount.total)
        cleared <- browser.badge
        after   <- popup.text("p")
      yield
        assertEquals(
          (total, badge, text),
          (9, "9", "9 words counted."),
        )
        assertEquals(
          (insecure, page),
          ("0 on this page.", "5 on this page."),
        )
        assertEquals(
          (reset, cleared, after),
          (0, "", "0 words counted."),
        )

  test(
    "The background counts the words in whatever text it is sent, in any script, but not punctuation.",
  ):
    for
      browser <- FakeBrowser.install(WordCount)
      plain   <- browser.call(WordCount.count)("one two  three")
      mixed   <- browser.call(WordCount.count)("héllo, привет — 世界 42!")
    yield assertEquals((plain, mixed), (3, 4))
