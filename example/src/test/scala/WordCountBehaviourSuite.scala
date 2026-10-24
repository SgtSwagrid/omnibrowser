package com.alecdorrington.omnibrowser.example

import cats.effect.IO
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
      s"Each page's words are added to the total, which the badge and popup show, $name.",
    ):
      for
        browser <- install
        _       <- browser.visit(
          "https://example.com/",
          "The quick brown fox",
        )
        _ <- browser.visit(
          "https://example.org/",
          "jumps over\n the lazy dog",
        )
        total <- browser.stored(WordCount.total)
        badge <- browser.badge
        popup <- browser.openPopup
        text  <- popup.text
      yield
        assertEquals(total, 9)
        assertEquals(badge, "9")
        assertEquals(text, "9 words counted.")

    test(s"Pages that are not served over HTTPS are not counted, $name."):
      install
        .flatTap(_.visit("http://example.com/", "Not counted"))
        .flatMap(_.stored(WordCount.total))
        .assertEquals(0)

  test("The background counts the words in whatever text it is sent."):
    FakeBrowser
      .install(WordCount)
      .flatMap(_.call(WordCount.count)("one two  three"))
      .assertEquals(3)
