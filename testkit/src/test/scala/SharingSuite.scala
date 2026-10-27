package com.alecdorrington.omnibrowser.testkit

import cats.effect.{Deferred, IO}
import cats.syntax.all.*
import com.alecdorrington.omnibrowser.*
import com.alecdorrington.omnibrowser.Permission.Storage
import fs2.concurrent.{Signal, SignallingRef}
import munit.CatsEffectSuite
import scala.concurrent.duration.DurationInt

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
    yield results.foreach(result => assert(failed(result, "add"), result))

  test("A request of a tab fails with why its handler failed."):
    for
      browser  <- FakeBrowser.install(Counter)
      page     <- browser.visit("https://example.com/count")
      negative <- browser.call(Counter.add, page.tab.get)(-1).attempt
      silent   <- browser.call(Counter.add, page.tab.get)(0).attempt
    yield
      assertEquals(
        negative,
        Left(MessagingError("add", "Nothing negative.")),
      )
      assertEquals(
        silent,
        Left(MessagingError(
          "add",
          "java.lang.IllegalStateException",
        )),
      )

  test("A request that its tab's script cannot read fails."):
    FakeBrowser
      .install(Counter)
      .flatMap(browser =>
        browser
          .visit("https://example.com/count")
          .flatMap(page => browser.call(Counter.misread, page.tab.get)("two"))
          .attempt,
      )
      .assertEquals(Left(MessagingError("add", "Expected a number.")))

  test("A request of a tab fails if the tab closes before it is answered."):
    for
      browser <- FakeBrowser.install(Counter)
      page    <- browser.visit("https://example.com/count")
      request <- browser.call(Counter.hang, page.tab.get)(()).attempt.start
      _       <- IO.sleep(20.millis) >> browser.close(page.tab.get)
      result  <- request.joinWithNever
    yield assert(
      result
        .left
        .exists:
          case MessagingError("hang", reason) =>
            reason.contains("message channel closed")
          case _ => false
      ,
      result,
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

  test("A shared value that its reader cannot read fails."):
    for
      browser <- FakeBrowser.install(Counter)
      page    <- browser.visit("https://example.com/count")
      result  <- browser.shared(Counter.misshared, page.tab.get).attempt
    yield assertEquals(
      result,
      Left(MessagingError("count", "Expected a string.")),
    )

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

  test("A page sees a value that its tab begins sharing after it looks."):
    for
      browser <- FakeBrowser.install(Counter)
      page    <- browser.visit("https://example.com/late")
      _       <- browser.openPopup
      _       <- browser.call(Counter.begin, page.tab.get)(())
      seen    <- browser.stored(Counter.seen)
    yield assertEquals(seen, List(7, 0))

  test("A page follows its tab to the next page that shares the value."):
    for
      browser <- FakeBrowser.install(Counter)
      page    <- browser.visit("https://example.com/count")
      _       <- browser.call(Counter.add, page.tab.get)(2)
      _       <- browser.openPopup
      _       <- browser.navigate(
        page.tab.get,
        "https://example.com/late",
      )
      _    <- browser.call(Counter.begin, page.tab.get)(())
      seen <- browser.stored(Counter.seen)
    yield assertEquals(seen, List(7, 0, 2))

  test("A page sees the default once its tab closes, and stops watching it."):
    for
      browser  <- FakeBrowser.install(Counter)
      page     <- browser.visit("https://example.com/count")
      _        <- browser.call(Counter.add, page.tab.get)(2)
      _        <- browser.openPopup
      _        <- browser.close(page.tab.get)
      seen     <- browser.stored(Counter.seen)
      finished <- browser.stored(Counter.finished)
      count    <- browser.shared(Counter.count, page.tab.get)
      open     <- browser.openPorts
    yield
      assertEquals(seen, List(0, 2))
      assertEquals((finished, count, open), (true, 0, 0))

  test("A value that its owner sets as it stops is not seen after it stops."):
    for
      browser <- FakeBrowser.install(Counter)
      page    <- browser.visit("https://example.com/count")
      _       <- browser.call(Counter.add, page.tab.get)(2)
      _       <- browser.openPopup
      _       <- browser.call(Counter.finish, page.tab.get)(())
      seen    <- browser.stored(Counter.seen)
    yield assertEquals(seen.headOption, Some(0))

  test("Each page that watches a value sees it, and one that stops lets go."):
    for
      browser <- FakeBrowser.install(Counter)
      page    <- browser.visit("https://example.com/count")
      _       <- browser.openPopup >> browser.openOptions
      _       <- browser.call(Counter.add, page.tab.get)(3)
      both    <- browser.openPorts
      _       <- browser.call(Counter.add, page.tab.get)(10)
      seen    <- browser.stored(Counter.seen)
      also    <- browser.stored(Counter.alsoSeen)
      one     <- browser.openPorts
    yield
      assertEquals(seen, List(13, 3, 0))
      assertEquals(also, List(3, 0))
      assertEquals((both, one), (2, 1))

  test(
    "Of two scripts in a page that share a value, the later's is its value, until it stops.",
  ):
    for
      browser <- FakeBrowser.install(Counter)
      page    <- browser.visit("https://example.com/twice")
      _       <- browser.openPopup
      _       <- browser.call(Counter.begin, page.tab.get)(())
      shared  <- browser.shared(Counter.count, page.tab.get)
      _       <- browser.call(Counter.stop, page.tab.get)(())
      seen    <- browser.stored(Counter.seen)
    yield
      assertEquals(shared, 7)
      assertEquals(seen, List(1, 7, 1))

  /** Whether `result` is a failure of the endpoint named `endpoint`. */
  private def failed
    (
      result: Either[Throwable, ?],
      endpoint: String,
    )
    : Boolean = result
    .left
    .exists:
      case MessagingError(`endpoint`, _) => true
      case _                             => false

/**
  * Counts what is added to each page whose path asks for it, whose popup
  * records each count of the active tab that it sees, as does its options page
  * until the count is at least `10`.
  */
object Counter extends BrowserExtension[Storage]:

  override val name = "Counter"

  /** What has been added to a page. */
  val count = Shared.tab[Int]("count", 0)

  /** Adds to a page's count, and gives the new count. */
  val add = Endpoint.tab[Int, Int]("add")

  /** Stops a page counting. */
  val stop = Endpoint.tab[Unit, Unit]("stop")

  /** Has a late page begin sharing its count. */
  val begin = Endpoint.tab[Unit, Unit]("begin")

  /** Is never answered. */
  val hang = Endpoint.tab[Unit, Unit]("hang")

  /** Sets a page's count, then stops it counting at once. */
  val finish = Endpoint.tab[Unit, Unit]("finish")

  /** [[add]], as it would be with the wrong request. */
  val misread = Endpoint.tab[String, Int]("add")

  /** [[count]], as it would be with the wrong value. */
  val misshared = Shared.tab[String]("count", "")

  /** Each count of the active tab that the popup has seen, latest first. */
  val seen = StorageKey.local[List[Int]]("seen", Nil)

  /** Each count of the active tab that the options page has seen, latest first. */
  val alsoSeen = StorageKey.local[List[Int]]("alsoSeen", Nil)

  /** Whether the popup has stopped watching the active tab. */
  val finished = StorageKey.local[Boolean]("finished", false)

  /** A value that a page shares in place of its count. */
  private val other = Shared.tab[String]("other", "")

  override def contentScripts() = Seq(
    ContentScript("https://*/count")(browser =>
      (SignallingRef[IO].of(0), Deferred[IO, Unit]).flatMapN((total, stopped) =>
        (browser.serve(add)(adding(total)) >>
          browser.serve(stop)(_ => stopped.complete(()).void) >>
          browser.serve(hang)(_ => IO.never) >> browser.serve(finish)(_ =>
            total.set(9) >> stopped.complete(()).void,
          ) >> browser.share(count)(total)).surround(stopped.get),
      ),
    ),
    ContentScript("https://*/silent")(
      _.share(other)(Signal.constant("Nothing to count.")).useForever,
    ),
    ContentScript("https://*/twice")(
      _.share(count)(Signal.constant(1)).useForever,
    ),
    ContentScript("https://*/late", "https://*/twice")(browser =>
      (Deferred[IO, Unit], Deferred[IO, Unit]).flatMapN((begun, stopped) =>
        (browser.serve(begin)(_ => begun.complete(()).void) >>
          browser.serve(stop)(_ => stopped.complete(()).void)).surround(
          begun.get >>
            browser.share(count)(Signal.constant(7)).surround(stopped.get),
        ),
      ),
    ),
  )

  override def popup() = Some(Page(browser =>
    watching(seen, browser) >> browser.storage.set(finished, true),
  ))

  override def options() = Some(Page(watching(alsoSeen, _, _ < 10)))

  /**
    * Adds each amount to `total`, and gives the new total, or fails as a
    * handler may, for an amount that is negative, or nothing.
    */
  private def adding(total: SignallingRef[IO, Int])(amount: Int): IO[Int] =
    if amount < 0 then
      IO.raiseError(IllegalArgumentException("Nothing negative."))
    else if amount == 0 then IO.raiseError(IllegalStateException())
    else total.updateAndGet(_ + amount)

  /**
    * Records in `key` each count of the active tab that `browser` sees, while
    * it is `wanted`.
    */
  private def watching
    (
      key: StorageKey[List[Int]],
      browser: Browser,
      wanted: Int => Boolean = _ => true,
    )
    : IO[Unit] = browser
    .tabs
    .active
    .flatMap(_.traverse_(tab =>
      browser
        .tabs
        .values(count, tab.id)
        .takeWhile(wanted)
        .evalMap(value => browser.storage.update(key)(value :: _))
        .compile
        .drain,
    ))
