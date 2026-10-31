package com.alecdorrington.omnibrowser.testkit

import cats.effect.IO
import cats.syntax.all.*
import com.alecdorrington.omnibrowser.*
import com.alecdorrington.omnibrowser.Permission.{Storage, Tabs}
import java.util.concurrent.TimeoutException
import munit.CatsEffectSuite
import org.scalajs.dom.document
import scala.concurrent.duration.*

class FakeBrowserSuite extends CatsEffectSuite:

  test(
    "Installing the extension reports its installation to the background, which answers each request with what its server gives, or its server's error.",
  ):
    for
      browser  <- FakeBrowser.install(Probe)
      installs <- browser.stored(Probe.installs)
      echoed   <- browser.call(Probe.echo)("stressed")
      failed   <- browser.call(Probe.fail)(()).attempt
    yield
      assertEquals((installs, echoed), (1, "desserts"))
      assertEquals(
        failed,
        Left(MessagingError("fail", "Deliberately failed.")),
      )

  test(
    "Content scripts are injected only into the pages that they match, and a failure in one fails the action that started it.",
  ):
    for
      browser <- FakeBrowser.install(Probe)
      _       <- browser.visit("https://example.com/", "first")
      _       <- browser.visit(
        "https://www.example.com/page",
        "second",
      )
      _      <- browser.visit("https://example.org/", "elsewhere")
      seen   <- browser.stored(Probe.seen)
      secret <- browser.visit("https://secret.test/").attempt
    yield
      assertEquals(seen, List("second", "first"))
      assert(
        secret.left.exists(_.getMessage.contains("not allowed")),
        s"Content scripts may not use session storage, yet gave $secret.",
      )

  test(
    "A page sees each change to the values that it watches, and a stored value that cannot be decoded is its default until set again.",
  ):
    for
      browser <- FakeBrowser.install(Probe)
      _       <- browser.store(Probe.misstored, "not a list")
      default <- browser.stored(Probe.seen)
      _       <- browser.visit("https://example.com/", "first")
      popup   <- browser.openPopup
      first   <- popup.text
      _       <- browser.store(Probe.seen, List("synced", "elsewhere"))
      synced  <- popup.text
      _       <- browser.store(Probe.misstored, "not a list")
      invalid <- popup.text
      _       <- browser.store(Probe.seen, List("again"))
      again   <- popup.text
    yield
      assertEquals(default, Nil)
      assertEquals(
        (first, synced, invalid, again),
        ("first", "synced, elsewhere", "", "again"),
      )

  test(
    "A request that the background has not answered can be given up at once.",
  ):
    for
      browser <- FakeBrowser.install(Probe)
      result  <- browser
        .call(Probe.ignore)(())
        .timeout(10.millis)
        .attempt
        .timeoutAndForget(1.second)
    yield assert(
      result.left.exists(_.isInstanceOf[TimeoutException]),
      s"The request was not given up, but gave $result.",
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

  test(
    "The toolbar button's click is heard by an extension without a popup, which sees the tab's URL only with the tabs permission.",
  ):
    Seq[(BrowserExtension[?], String)](
      Clicker -> "https://example.com/",
      Blind   -> "unknown",
    ).traverse_((extension, url) =>
      for
        browser <- FakeBrowser.install(extension)
        _       <- browser.visit("https://example.com/")
        _       <- browser.clickAction
        badge   <- browser.badge
      yield assertEquals(badge, url, extension.name),
    )

/** An extension that exercises each part of the fake browser. */
object Probe extends BrowserExtension[Storage]:

  override val name = "Probe"

  /** Reverses its request. */
  val echo = Message[String, String]("echo")

  /** Always fails. */
  val fail = Message[Unit, Unit]("fail")

  /** Never answered. */
  val ignore = Message[Unit, Unit]("ignore")

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
    serve(ignore)((_, _) => IO.never),
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
