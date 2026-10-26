package com.alecdorrington.omnibrowser.testkit

import cats.effect.IO
import com.alecdorrington.omnibrowser.*
import munit.CatsEffectSuite

class SendersSuite extends CatsEffectSuite:

  test("A content script's request tells the background which tab it is from."):
    for
      browser <- FakeBrowser.install(Flagger)
      first   <- browser.visit("https://example.com/flag")
      second  <- browser.visit("https://example.org/")
      flagged <- browser.badgeOf(first.tab.get)
      plain   <- browser.badgeOf(second.tab.get)
    yield
      assertEquals(flagged, "!")
      assertEquals(plain, "")

  test("A request from elsewhere than a content script comes from no tab."):
    FakeBrowser
      .install(Flagger)
      .flatMap(_.call(Flagger.where)(()))
      .assertEquals("nowhere")

/** Flags the tab of each page whose path asks for it. */
object Flagger extends BrowserExtension[Any]:

  override val name = "Flagger"

  /** Flags the sender's tab. */
  val flag = Endpoint[Unit, Unit]("flag")

  /** Gives the URL of the sender's tab, if it has one. */
  val where = Endpoint[Unit, String]("where")

  override def background() = Background(
    serve(flag)((_, sender, browser) =>
      sender.tab.fold(IO.unit)(tab => browser.action.setBadge("!", tab.id)),
    ),
    serve(where)((_, sender, _) =>
      IO.pure(sender.tab.flatMap(_.url).getOrElse("nowhere")),
    ),
  )

  override def contentScripts() =
    Seq(ContentScript("https://*/flag")(_.runtime.call(flag)(())))
