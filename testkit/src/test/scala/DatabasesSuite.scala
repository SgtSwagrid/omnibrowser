package com.alecdorrington.omnibrowser.testkit

import cats.effect.IO
import cats.syntax.all.*
import com.alecdorrington.omnibrowser.*
import com.alecdorrington.omnibrowser.Permission.Storage
import com.alecdorrington.omnibrowser.webext.{
  IdbDatabaseApi, IdbFactoryApi, IdbObjectStoreApi,
}
import munit.CatsEffectSuite
import scala.collection.immutable.SortedMap
import scala.scalajs.js

/**
  * Checks the extension's databases, as the library keeps them in the fake
  * browser's IndexedDB, and the fake itself, as the browser's that it stands in
  * for.
  */
class DatabasesSuite extends CatsEffectSuite:

  test(
    "Values kept are read back by their keys, several at once, none for a key under which nothing is kept, and every one in the order of their keys.",
  ):
    for
      browser <- FakeBrowser.install(Bare)
      kept = browser.databases
      _ <- kept.write(
        Words.counts.set("cat", 2),
        Words.counts.set("ant", 1),
        Words.counts.set("dog", 3),
        Words.names.set("cat", "Felix"),
      )
      some <- kept.get(
        Words.counts,
        List("dog", "cat", "eel", "cat"),
      )
      one  <- kept.get(Words.names, "cat")
      none <- kept.get(Words.names, "dog")
      all  <- kept.all(Words.counts)
    yield
      assertEquals(some, Map("dog" -> 3, "cat" -> 2))
      assertEquals((one, none), (Some("Felix"), None))
      assertEquals(
        all.toList,
        List("ant" -> 1, "cat" -> 2, "dog" -> 3),
      )

  test(
    "Writes to several stores are kept all at once, in order, or none of them where the browser refuses them, as when it has no room, until it no longer does.",
  ):
    for
      browser <- FakeBrowser.install(Bare)
      kept = browser.databases
      _ <- kept.write(
        Words.counts.set("cat", 1),
        Words.counts.set("dog", 1),
        Words.counts.remove("dog"),
        Words.names.set("cat", "Felix"),
      )
      _       <- browser.refuseWrites(true)
      refused <- kept
        .write(
          Words.counts.set("cat", 9),
          Words.names.clear,
        )
        .attempt
      afterRefusal <- state(kept)
      _            <- browser.refuseWrites(false)
      _            <- kept.write(
        Words.counts.clear,
        Words.names.set("eel", "Ed"),
      )
      afterClear <- state(kept)
    yield
      assert(
        refused
          .left
          .exists {
            case DatabaseError("words", reason) =>
              reason.startsWith("QuotaExceededError")
            case _ => false
          },
        refused,
      )
      assertEquals(
        afterRefusal,
        (SortedMap("cat" -> 1), SortedMap("cat" -> "Felix")),
      )
      assertEquals(
        afterClear,
        (
          SortedMap.empty[String, Int],
          SortedMap("cat" -> "Felix", "eel" -> "Ed"),
        ),
      )

  test(
    "Updates read and write their values at once, so that none of many made together is lost, and take out a value given none.",
  ):
    for
      browser <- FakeBrowser.install(Bare)
      kept = browser.databases
      _ <- (1 to 25)
        .toList
        .parTraverse_(_ =>
          kept.update(Words.counts, List("cat", "dog"))((_, count) =>
            Some(count.getOrElse(0) + 1),
          ),
        )
      counted <- kept.all(Words.counts)
      updated <- kept.update(Words.counts, List("cat", "dog", "eel")):
        (word, count) => if word == "cat" then None else count.map(_ * 2)
      left <- kept.all(Words.counts)
    yield
      assertEquals(
        counted.toMap,
        Map("cat" -> 25, "dog" -> 25),
      )
      assertEquals(updated, Map("dog" -> 50))
      assertEquals(left.toMap, Map("dog" -> 50))

  test(
    "The background's databases are the pages' and the tests', and a value that cannot be decoded reads as none until it is written again.",
  ):
    for
      browser <- FakeBrowser.install(Keeper)
      _       <- browser.databases.all(Words.counts)
      _       <- browser.databases.write(OldWords.counts.set("cat", "many"))
      none    <- browser.databases.get(Words.counts, "cat")
      all     <- browser.databases.all(Words.counts)
      _       <- browser.call(Keeper.count)(List("cat", "dog", "cat"))
      popup   <- browser.openPopup
      shown   <- popup.text
      counted <- browser.databases.all(Words.counts)
    yield
      assertEquals(
        (none, all),
        (None, SortedMap.empty[String, Int]),
      )
      assertEquals(counted.toMap, Map("cat" -> 2, "dog" -> 1))
      assertEquals(shown, "cat 2, dog 1")

  test(
    "A database opened at a later version gains its new stores, keeping what the old ones held, once the connections to the older version close, which can no longer open it.",
  ):
    for
      browser <- FakeBrowser.install(Bare)
      kept = browser.databases
      _      <- kept.write(Before.a.set("x", 1))
      _      <- kept.write(After.b.set("y", 2))
      a      <- kept.get(After.a, "x")
      b      <- kept.get(After.b, "y")
      before <- kept.get(Before.a, "x").attempt
    yield
      assertEquals((a, b), (Some(1), Some(2)))
      assert(
        before
          .left
          .exists {
            case DatabaseError("upgraded", reason) =>
              reason.startsWith("VersionError")
            case _ => false
          },
        before,
      )

  test(
    "A database given a store without its version being raised says so, and writes to two databases at once are refused.",
  ):
    for
      browser <- FakeBrowser.install(Bare)
      kept = browser.databases
      _       <- kept.write(Stale.a.set("x", 1))
      grown   <- kept.get(Grown.b, "y").attempt
      several <- kept
        .write(
          Stale.a.set("x", 2),
          Words.counts.set("x", 2),
        )
        .attempt
      x <- kept.get(Stale.a, "x")
    yield
      assertEquals(
        grown.left.map(_.getMessage),
        Left(
          "The database stale failed: It lacks the store b, which was added without raising its version.",
        ),
      )
      assert(several.left.exists(_.isInstanceOf[IllegalArgumentException]))
      assertEquals(x, Some(1))

  test("A content script's browser has no databases."):
    assert(compileErrors("(browser: TabBrowser) => browser.databases").contains(
      "value databases is not a member",
    ))

  test(
    "A request made in a transaction once the task that began it is over fails, as the browser's does, but one made in a request's handler does not.",
  ):
    for
      databases <- IO(Databases(later, error => throw error, identity))
      factory = databases.factory.asInstanceOf[IdbFactoryApi]
      connection <- opened(factory, "late", "s")
      store      <-
        IO(connection.transaction(js.Array("s"), "readwrite").objectStore("s"))
      _         <- IO.cede
      late      <- IO(store.put(1, "late")).attempt
      inHandler <- IO.async_[Boolean]: done =>
        val transaction = connection.transaction(js.Array("s"), "readwrite")
        val objects     = transaction.objectStore("s")
        objects.put(1, "first").onsuccess = _ => objects.put(2, "second")
        transaction.oncomplete = _ => done(Right(true))
        transaction.onabort = _ => done(Right(false))
      readOnly <-
        IO(connection.transaction(js.Array("s"), "readonly").objectStore("s"))
          .flatMap(objects => IO(objects.put(1, "x")).attempt)
    yield
      assertEquals(
        named(late),
        Some("TransactionInactiveError"),
      )
      assert(inHandler)
      assertEquals(named(readOnly), Some("ReadOnlyError"))

  test(
    "Built, the background and pages are given the extension's IndexedDB, and content scripts none.",
  ):
    IO:
      val activity   = Activity()
      val simulation = Simulation(
        InProcess(Bare, activity.launch),
        activity,
      )
      def scope(kind: Kind): js.Dynamic =
        val scope = js.Dynamic.literal()
        Sandbox(
          "unused",
          simulation.context(kind),
          scope,
          Target.Chromium,
          activity,
        )
        scope.indexedDB
      assert(scope(Kind.Background) eq simulation.databases.factory)
      assert(scope(Kind.Page) eq simulation.databases.factory)
      assert(js.isUndefined(scope(Kind.Content(1))))

  /** Every value of [[Words]]' stores. */
  private def state
    (kept: Browser.Databases)
    : IO[(SortedMap[String, Int], SortedMap[String, String])] =
    (kept.all(Words.counts), kept.all(Words.names)).tupled

  /** A connection to the database `name` of `factory`, with a store `store`. */
  private def opened
    (
      factory: IdbFactoryApi,
      name: String,
      store: String,
    )
    : IO[IdbDatabaseApi] = IO.async_[IdbDatabaseApi]: done =>
    val request = factory.open(name, 1)
    request.onupgradeneeded =
      _ => request.result.asInstanceOf[IdbDatabaseApi].createObjectStore(store)
    request.onsuccess =
      _ => done(Right(request.result.asInstanceOf[IdbDatabaseApi]))

  /** Does `action` a moment later. */
  private def later(action: => Unit): Unit = js.timers.setTimeout(0)(action)

  /** The name of the browser's exception that `result` failed with, if any. */
  private def named(result: Either[Throwable, ?]): Option[String] = result
    .left
    .toOption
    .collect:
      case js.JavaScriptException(exception) =>
        exception.asInstanceOf[js.Dynamic].name.asInstanceOf[String]

/** A database of words, and of their counts. */
object Words extends Database("words", version = 1):

  val counts = store[Int]("counts")

  val names = store[String]("names")

/** [[Words]], as an earlier version kept it, counting in words. */
object OldWords extends Database("words", version = 1):

  val counts = store[String]("counts")

/** A database before its upgrade. */
object Before extends Database("upgraded", version = 1):

  val a = store[Int]("a")

/** [[Before]], upgraded to a store more. */
object After extends Database("upgraded", version = 2):

  val a = store[Int]("a")

  val b = store[Int]("b")

/** A database. */
object Stale extends Database("stale", version = 1):

  val a = store[Int]("a")

/** [[Stale]], given a store more without its version being raised. */
object Grown extends Database("stale", version = 1):

  val a = store[Int]("a")

  val b = store[Int]("b")

/** Counts words in its background, and shows them in its popup. */
object Keeper extends BrowserExtension[Storage]:

  override val name = "Keeper"

  /** Counts each word once more. */
  val count = Message[List[String], Unit]("count")

  override def background() = Background(serve(count)((words, browser) =>
    val counts = words.groupMapReduce(identity)(_ => 1)(_ + _)
    browser
      .databases
      .update(Words.counts, counts.keys.toList)((word, count) =>
        Some(count.getOrElse(0) + counts(word)),
      )
      .void,
  ))

  override def popup() = Some(Page(browser =>
    browser
      .databases
      .all(Words.counts)
      .flatMap(counts =>
        IO(org.scalajs.dom.document.body.textContent =
          counts.map((word, count) => s"$word $count").mkString(", "),
        ),
      ),
  ))
