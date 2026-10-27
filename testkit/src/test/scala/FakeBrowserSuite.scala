package com.alecdorrington.omnibrowser.testkit

import cats.effect.IO
import com.alecdorrington.omnibrowser.*
import com.alecdorrington.omnibrowser.Permission.{Storage, Tabs}
import munit.CatsEffectSuite
import org.scalajs.dom.document

class FakeBrowserSuite extends CatsEffectSuite:

  test("Installing the extension reports its installation to the background."):
    FakeBrowser.install(Probe).flatMap(_.stored(Probe.installs)).assertEquals(1)

  test("A request is answered by the background that serves its message."):
    FakeBrowser
      .install(Probe)
      .flatMap(_.call(Probe.echo)("stressed"))
      .assertEquals("desserts")

  test("A request that its server fails gives the server's error."):
    FakeBrowser
      .install(Probe)
      .flatMap(_.call(Probe.fail)(()).attempt)
      .map(result =>
        assertEquals(
          result,
          Left(MessagingError("fail", "Deliberately failed.")),
        ),
      )

  test("Content scripts are injected only into the pages that they match."):
    for
      browser <- FakeBrowser.install(Probe)
      _       <- browser.visit("https://example.com/", "first")
      _       <- browser.visit(
        "https://www.example.com/page",
        "second",
      )
      _    <- browser.visit("https://example.org/", "elsewhere")
      seen <- browser.stored(Probe.seen)
    yield assertEquals(seen, List("second", "first"))

  test("A failure in a content script fails the action that started it."):
    FakeBrowser
      .install(Probe)
      .flatMap(_.visit("https://secret.test/").attempt)
      .map(result =>
        assert(
          result.left.exists(_.getMessage.contains("not allowed")),
          s"Content scripts may not use session storage, yet gave $result.",
        ),
      )

  test("A page sees each change to the values that it watches."):
    for
      browser <- FakeBrowser.install(Probe)
      _       <- browser.visit("https://example.com/", "first")
      popup   <- browser.openPopup
      before  <- popup.text
      _       <- browser.store(Probe.seen, List("synced", "elsewhere"))
      after   <- popup.text
    yield
      assertEquals(before, "first")
      assertEquals(after, "synced, elsewhere")

  test("A stored value that cannot be decoded is its default until set again."):
    for
      browser <- FakeBrowser.install(Probe)
      _       <- browser.store(Probe.misstored, "not a list")
      before  <- browser.stored(Probe.seen)
      _       <- browser.visit("https://example.com/", "first")
      after   <- browser.stored(Probe.seen)
    yield
      assertEquals(before, Nil)
      assertEquals(after, List("first"))

  test("A page goes on watching a value after one that cannot be decoded."):
    for
      browser <- FakeBrowser.install(Probe)
      popup   <- browser.openPopup
      _       <- browser.store(Probe.seen, List("synced"))
      before  <- popup.text
      _       <- browser.store(Probe.misstored, "not a list")
      invalid <- popup.text
      _       <- browser.store(Probe.seen, List("again"))
      after   <- popup.text
    yield assertEquals(
      (before, invalid, after),
      ("synced", "", "again"),
    )

  test("An extension without the page asked for cannot open it."):
    FakeBrowser
      .install(Probe)
      .flatMap(_.openOptions.attempt)
      .map(result =>
        assertEquals(
          result.left.map(_.getMessage),
          Left("The extension has no options page."),
        ),
      )

  test("The toolbar button's click is heard by an extension without a popup."):
    for
      browser <- FakeBrowser.install(Clicker)
      _       <- browser.visit("https://example.com/")
      _       <- browser.clickAction
      badge   <- browser.badge
    yield assertEquals(badge, "https://example.com/")

  test("An extension without the tabs permission cannot see a tab's URL."):
    for
      browser <- FakeBrowser.install(Blind)
      _       <- browser.visit("https://example.com/")
      _       <- browser.clickAction
      badge   <- browser.badge
      tabs    <- browser.tabs
    yield
      assertEquals(badge, "unknown")
      assertEquals(tabs.map(_.url), List(None))

/** An extension that exercises each part of the fake browser. */
object Probe extends BrowserExtension[Storage]:

  override val name = "Probe"

  /** Reverses its request. */
  val echo = Message[String, String]("echo")

  /** Always fails. */
  val fail = Message[Unit, Unit]("fail")

  /** How many times the extension has been installed. */
  val installs = StorageKey.local[Int]("installs", 0)

  /** The text of each page visited, most recent first. */
  val seen = StorageKey.local[List[String]]("seen", Nil)

  /** A value stored under [[seen]]'s name, which [[seen]] cannot decode. */
  val misstored = StorageKey.local[String]("seen", "")

  /** A value that content scripts may not see. */
  val secret = StorageKey.session[String]("secret", "")

  override def background() = Background(
    onInstalled((_, browser) => browser.storage.update(installs)(_ + 1).void),
    serve(echo)((text, _) => IO.pure(text.reverse)),
    serve(fail)((_, _) =>
      IO.raiseError(RuntimeException("Deliberately failed.")),
    ),
  )

  override def contentScripts() = Seq(
    ContentScript("https://*.example.com/*")(browser =>
      IO(document.body.innerText)
        .flatMap(text => browser.storage.update(seen)(text :: _))
        .void,
    ),
    ContentScript("https://secret.test/*")(_.storage.get(secret).void),
  )

  override def popup() = Some(Page(browser =>
    browser
      .storage
      .values(seen)
      .evalMap(pages => IO(document.body.textContent = pages.mkString(", ")))
      .compile
      .drain,
  ))

/** Shows the URL of the tab in which its toolbar button was clicked. */
object Clicker extends BrowserExtension[Tabs]:

  override val name = "Clicker"

  override def background() = Background(onActionClicked((tab, browser) =>
    browser.action.setBadge(tab.url.getOrElse("unknown")),
  ))

/** The same as [[Clicker]], but without the permission to see tabs' URLs. */
object Blind extends BrowserExtension[Any]:

  override val name = "Blind"

  override def background() = Background(onActionClicked((tab, browser) =>
    browser.action.setBadge(tab.url.getOrElse("unknown")),
  ))
