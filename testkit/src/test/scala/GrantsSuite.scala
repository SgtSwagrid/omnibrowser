package com.alecdorrington.omnibrowser.testkit

import cats.effect.IO
import cats.syntax.all.*
import com.alecdorrington.omnibrowser.*
import com.alecdorrington.omnibrowser.Permission.{Storage, Tabs}
import munit.CatsEffectSuite

class GrantsSuite extends CatsEffectSuite:

  test(
    "An optional permission gives evidence of it only once the user grants it, which lets the extension use its APIs.",
  ):
    for
      browser    <- FakeBrowser.install(Asker)
      _          <- browser.visit("https://example.com/")
      _          <- browser.answerRequests(false)
      refused    <- browser.call(Asker.ask)(())
      before     <- browser.tabs
      _          <- browser.answerRequests(true)
      granted    <- browser.call(Asker.ask)(())
      after      <- browser.tabs
      remembered <- browser.call(Asker.remember)("hello")
      note       <- browser.stored(Asker.note)
    yield
      assertEquals(
        (refused, granted, remembered),
        (false, true, true),
      )
      assertEquals(before.map(_.url), List(None))
      assertEquals(
        after.map(_.url),
        List(Some("https://example.com/")),
      )
      assertEquals(note, "hello")

  test("A permission that the extension did not declare cannot be asked for."):
    FakeBrowser
      .install(Asker)
      .flatMap(_.call(Asker.askUndeclared)(()).attempt)
      .map(result =>
        assert(
          result
            .left
            .exists(_.getMessage.contains("Only permissions specified")),
          result,
        ),
      )

  test("A program that a page runs, as from a DOM event, is waited for."):
    FakeBrowser
      .install(Asker)
      .flatTap(_.openPopup)
      .flatMap(_.badge)
      .assertEquals("ran")

/** An extension that asks for permissions while it runs. */
object Asker extends BrowserExtension[Any]:

  override val name = "Asker"

  override val optionalPermissions = Permissions.of[Tabs & Storage]

  /** Asks for the tabs permission, and gives whether it was granted. */
  val ask = Message[Unit, Boolean]("ask")

  /** Asks for the storage permission, then stores the request. */
  val remember = Message[String, Boolean]("remember")

  /** Asks for a permission that the extension did not declare. */
  val askUndeclared = Message[Unit, Boolean]("askUndeclared")

  val note = StorageKey.local[String]("note", "")

  override def background() = Background(
    serve(ask)((_, browser) => browser.grants.request[Tabs].map(_.isDefined)),
    serve(remember)((text, browser) =>
      browser
        .grants
        .request[Storage]
        .flatMap(_.traverse(granted =>
          given Granted[Storage] = granted
          browser.storage.set(note, text),
        ))
        .map(_.isDefined),
    ),
    serve(askUndeclared)((_, browser) =>
      browser.grants.request[Permission.ActiveTab].map(_.isDefined),
    ),
  )

  override def popup() =
    Some(Page(browser => IO(browser.run(browser.action.setBadge("ran")))))
