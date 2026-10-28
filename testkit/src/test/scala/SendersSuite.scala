package com.alecdorrington.omnibrowser.testkit

import cats.effect.IO
import com.alecdorrington.omnibrowser.*
import munit.CatsEffectSuite

class SendersSuite extends CatsEffectSuite:

  test(
    "A content script's request tells the background which tab it is from, and one from elsewhere comes from no tab.",
  ):
    for
      browser <- FakeBrowser.install(Flagger)
      first   <- browser.visit("https://example.com/flag")
      second  <- browser.visit("https://example.org/")
      flagged <- browser.badgeOf(first.tab.get)
      plain   <- browser.badgeOf(second.tab.get)
      where   <- browser.call(Flagger.where)(())
    yield
      assertEquals(flagged, "!")
      assertEquals(plain, "")
      assertEquals(where, "nowhere")

/** Flags the tab of each page whose path asks for it. */
object Flagger extends BrowserExtension[Any]:

  override val name = "Flagger"

  /** Flags the sender's tab. */
  val flag = Message[Unit, Unit]("flag")

  /** Gives the URL of the sender's tab, if it has one. */
  val where = Message[Unit, String]("where")

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
