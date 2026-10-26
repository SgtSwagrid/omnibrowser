package com.alecdorrington.omnibrowser.testkit

import cats.effect.IO
import cats.syntax.all.*
import com.alecdorrington.omnibrowser.*
import com.alecdorrington.omnibrowser.Permission.{Storage, Tabs}
import munit.CatsEffectSuite

class GrantsSuite extends CatsEffectSuite:

  test("An optional permission that the user grants gives evidence of it."):
    FakeBrowser.install(Asker).flatMap(_.call(Asker.ask)(())).assertEquals(true)

  test("An optional permission that the user refuses gives none."):
    FakeBrowser
      .install(Asker)
      .flatTap(_.answerRequests(false))
      .flatMap(_.call(Asker.ask)(()))
      .assertEquals(false)

  test("The evidence of a granted permission lets the extension use its APIs."):
    for
      browser    <- FakeBrowser.install(Asker)
      remembered <- browser.call(Asker.remember)("hello")
      note       <- browser.stored(Asker.note)
    yield
      assert(remembered)
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

  test("Once granted the tabs permission, the extension sees tabs' URLs."):
    for
      browser <- FakeBrowser.install(Asker)
      _       <- browser.visit("https://example.com/")
      before  <- browser.tabs
      _       <- browser.call(Asker.ask)(())
      after   <- browser.tabs
    yield
      assertEquals(before.map(_.url), List(None))
      assertEquals(
        after.map(_.url),
        List(Some("https://example.com/")),
      )

  test("A badge set for one tab is shown only in that tab."):
    for
      browser <- FakeBrowser.install(Marker)
      page    <- browser.visit("https://example.com/")
      _       <- browser.clickAction
      tab     <- IO.fromOption(page.tab)(RuntimeException("No tab."))
      here    <- browser.badgeOf(tab)
      global  <- browser.badge
    yield
      assertEquals(here, "here")
      assertEquals(global, "")

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
  val ask = Endpoint[Unit, Boolean]("ask")

  /** Asks for the storage permission, then stores the request. */
  val remember = Endpoint[String, Boolean]("remember")

  /** Asks for a permission that the extension did not declare. */
  val askUndeclared = Endpoint[Unit, Boolean]("askUndeclared")

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

/** Marks the tab in which its toolbar button is clicked. */
object Marker extends BrowserExtension[Any]:

  override val name = "Marker"

  override def background() = Background(
    onActionClicked((tab, browser) => browser.action.setBadge("here", tab.id)),
  )
