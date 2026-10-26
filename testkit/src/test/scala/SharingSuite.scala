package com.alecdorrington.omnibrowser.testkit

import cats.effect.{Deferred, IO}
import cats.syntax.all.*
import com.alecdorrington.omnibrowser.*
import com.alecdorrington.omnibrowser.Permission.Storage
import fs2.concurrent.SignallingRef
import munit.CatsEffectSuite

class SharingSuite extends CatsEffectSuite:

  test("A tab's content script answers the requests made of its tab."):
    for
      browser <- FakeBrowser.install(Counter)
      page    <- browser.visit("https://example.com/count")
      first   <- browser.call(Counter.add, page.tab.get)(2)
      second  <- browser.call(Counter.add, page.tab.get)(3)
    yield
      assertEquals(first, 2)
      assertEquals(second, 5)

  test("A request of a tab fails if nothing there serves it."):
    for
      browser <- FakeBrowser.install(Counter)
      plain   <- browser.visit("https://example.com/other")
      counter <- browser.visit("https://example.com/count")
      _       <- browser.call(Counter.stop, counter.tab.get)(())
      results <- Seq(plain, counter).traverse(page =>
        browser.call(Counter.add, page.tab.get)(1).attempt,
      )
    yield results.foreach(result =>
      assert(
        result
          .left
          .exists:
            case MessagingError("add", _) => true
            case _                        => false,
        result,
      ),
    )

  test("A tab's shared value is its content script's, or else its default."):
    for
      browser <- FakeBrowser.install(Counter)
      counter <- browser.visit("https://example.com/count")
      plain   <- browser.visit("https://example.com/other")
      _       <- browser.call(Counter.add, counter.tab.get)(4)
      counted <- browser.shared(Counter.count, counter.tab.get)
      other   <- browser.shared(Counter.count, plain.tab.get)
      _       <- browser.call(Counter.stop, counter.tab.get)(())
      stopped <- browser.shared(Counter.count, counter.tab.get)
    yield assertEquals((counted, other, stopped), (4, 0, 0))

  test("A tab whose script shares only something else gives the default."):
    for
      browser <- FakeBrowser.install(Counter)
      page    <- browser.visit("https://example.com/silent")
      count   <- browser.shared(Counter.count, page.tab.get)
      seen    <- browser.openPopup >> browser.stored(Counter.seen)
    yield
      assertEquals(count, 0)
      assertEquals(seen, List(0))

  test(
    "A page sees a tab's shared value, then each new value, then the default once the tab stops sharing it.",
  ):
    for
      browser <- FakeBrowser.install(Counter)
      page    <- browser.visit("https://example.com/count")
      _       <- browser.call(Counter.add, page.tab.get)(2)
      _       <- browser.openPopup
      _       <- browser.call(Counter.add, page.tab.get)(3)
      _       <- browser.call(Counter.stop, page.tab.get)(())
      seen    <- browser.stored(Counter.seen)
    yield assertEquals(seen, List(0, 5, 2))

/**
  * Counts what is added to each page whose path asks for it, whose popup
  * records each count of the active tab that it sees.
  */
object Counter extends BrowserExtension[Storage]:

  override val name = "Counter"

  /** What has been added to a page. */
  val count = Shared.tab[Int]("count", 0)

  /** Adds to a page's count, and gives the new count. */
  val add = Endpoint.tab[Int, Int]("add")

  /** Stops a page counting. */
  val stop = Endpoint.tab[Unit, Unit]("stop")

  /** Each count of the active tab that the popup has seen, latest first. */
  val seen = StorageKey.local[List[Int]]("seen", Nil)

  /** A value that a page shares in place of its count. */
  private val other = Shared.tab[String]("other", "")

  override def contentScripts() = Seq(
    ContentScript("https://*/count")(browser =>
      (SignallingRef[IO].of(0), Deferred[IO, Unit]).flatMapN((total, stopped) =>
        (browser.serve(add)(amount => total.updateAndGet(_ + amount)) >>
          browser.serve(stop)(_ => stopped.complete(()).void) >>
          browser.share(count)(total)).surround(stopped.get),
      ),
    ),
    ContentScript("https://*/silent")(browser =>
      SignallingRef[IO]
        .of("Nothing to count.")
        .flatMap(browser.share(other)(_).useForever),
    ),
  )

  override def popup() = Some(Page(browser =>
    browser
      .tabs
      .active
      .flatMap(_.traverse_(tab =>
        browser
          .tabs
          .values(count, tab.id)
          .evalMap(value => browser.storage.update(seen)(value :: _))
          .compile
          .drain,
      )),
  ))
