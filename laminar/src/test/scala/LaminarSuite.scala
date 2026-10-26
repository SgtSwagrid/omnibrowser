package com.alecdorrington.omnibrowser.laminar

import cats.effect.{Deferred, IO}
import cats.syntax.all.*
import com.alecdorrington.omnibrowser.{
  Background, Browser, BrowserExtension, ContentScript, Endpoint, Shared,
  StorageKey, TabBrowser, TabId,
}
import com.alecdorrington.omnibrowser.Permission.Storage
import com.alecdorrington.omnibrowser.testkit.FakeBrowser
import com.raquo.laminar.api.L.*
import fs2.concurrent.SignallingRef
import munit.CatsEffectSuite

class LaminarSuite extends CatsEffectSuite:

  test("A page shows a stored value, then each new value that it is given."):
    for
      browser <- FakeBrowser.install(Tally)
      popup   <- browser.openPopup
      before  <- popup.text(".clicks")
      _       <- browser.store(Tally.clicks, 5)
      after   <- popup.text(".clicks")
    yield
      assertEquals(before, "0 clicks")
      assertEquals(after, "5 clicks")

  test("An updater transforms a stored value with each event, whatever it is."):
    for
      browser <- FakeBrowser.install(Tally)
      popup   <- browser.openPopup
      _       <- popup.click(".add")
      _       <- popup.click(".add")
      clicks  <- browser.stored(Tally.clicks)
      text    <- popup.text(".clicks")
    yield
      assertEquals(clicks, 2)
      assertEquals(text, "2 clicks")

  test("An event stream gives the result of its program."):
    FakeBrowser
      .install(Tally)
      .flatMap(_.openPopup)
      .flatMap(_.text(".doubled"))
      .assertEquals("42")

  test("A writer stores what the user enters, which the page shows."):
    for
      browser <- FakeBrowser.install(Tally)
      options <- browser.openOptions
      before  <- options.isChecked(".enabled")
      _       <- options.click(".enabled")
      after   <- options.isChecked(".enabled")
      enabled <- browser.stored(Tally.enabled)
      _       <- options.enter(".note", "Remember the milk.")
      note    <- browser.stored(Tally.note)
      _       <- browser.store(Tally.note, "Forget the milk.")
      synced  <- options.value(".note")
    yield
      assertEquals(
        (before, after, enabled),
        (true, false, false),
      )
      assertEquals(note, "Remember the milk.")
      assertEquals(synced, "Forget the milk.")

  test("An updater transforms a stored value by what each event carries."):
    for
      browser <- FakeBrowser.install(Tally)
      options <- browser.openOptions
      _       <- options.enter(".tag", "dairy")
      _       <- options.enter(".tag", "urgent")
      tags    <- browser.stored(Tally.tags)
    yield assertEquals(tags, List("urgent", "dairy"))

  test("A content script adds its element to the end of its page."):
    for
      browser <- FakeBrowser.install(Tally)
      page    <- browser.visit("https://example.com/", "Hello. ")
      _       <- browser.store(Tally.clicks, 3)
      tally   <- page.text("#tally")
      text    <- page.text
    yield
      assertEquals(tally, "3")
      assertEquals(text, "Hello. 3")

  test("An event stream runs its program each time that it starts."):
    for
      browser <- FakeBrowser.install(Copier)
      popup   <- browser.openPopup
      _       <- popup.click(".toggle")
      _       <- popup.click(".toggle")
      starts  <- browser.stored(Copier.starts)
    yield assertEquals(starts, 2)

  test("An event stream runs its stream only while it is observed."):
    for
      browser <- FakeBrowser.install(Copier)
      popup   <- browser.openPopup
      _       <- browser.store(Copier.original, 1)
      _       <- popup.click(".toggle")
      _       <- browser.store(Copier.original, 2)
      hidden  <- browser.stored(Copier.copy)
      _       <- popup.click(".toggle")
      _       <- browser.store(Copier.original, 3)
      shown   <- browser.stored(Copier.copy)
    yield
      assertEquals(hidden, 1)
      assertEquals(shown, 3)

  test(
    "A page shows a tab's shared value, then each new value that it is given.",
  ):
    for
      browser <- FakeBrowser.install(Presser)
      page    <- browser.visit("https://example.com/")
      _       <- browser.call(Presser.press, page.tab.get)(())
      popup   <- browser.openPopup
      before  <- popup.text(".presses")
      _       <- browser.call(Presser.press, page.tab.get)(())
      after   <- popup.text(".presses")
    yield
      assertEquals(before, "1 presses")
      assertEquals(after, "2 presses")

  test("A page shows a tab's default value where nothing there shares it."):
    for
      browser <- FakeBrowser.install(Presser)
      _       <- browser.visit("http://example.com/")
      popup   <- browser.openPopup
      presses <- popup.text(".presses")
    yield assertEquals(presses, "0 presses")

  test(
    "A page shows a tab's default value again once nothing there shares it.",
  ):
    for
      browser <- FakeBrowser.install(Presser)
      page    <- browser.visit("https://example.com/")
      _       <- browser.call(Presser.press, page.tab.get)(())
      popup   <- browser.openPopup
      before  <- popup.text(".presses")
      _       <- browser.call(Presser.release, page.tab.get)(())
      after   <- popup.text(".presses")
    yield
      assertEquals(before, "1 presses")
      assertEquals(after, "0 presses")

  test("A content script cannot watch a tab's shared value."):
    assert(
      compileErrors("(??? : TabBrowser).signal(Presser.presses, TabId(1))")
        .contains("A content script shares a tab's values"),
    )

/** Counts clicks, with each of the ways in which Laminar uses the browser. */
object Tally extends BrowserExtension[Storage]:

  override val name = "Tally"

  /** Doubles its request. */
  val double = Endpoint[Int, Int]("double")

  /** How many times the popup's button has been clicked. */
  val clicks = StorageKey.local[Int]("clicks", 0)

  val enabled = StorageKey.sync[Boolean]("enabled", true)

  val note = StorageKey.sync[String]("note", "")

  /** Each tag entered, the most recent first. */
  val tags = StorageKey.local[List[String]]("tags", Nil)

  override def background() =
    Background(serve(double)((number, _) => IO.pure(number * 2)))

  override def contentScripts() = Seq(
    LaminarContentScript("https://*/*")(browser =>
      div(
        idAttr := "tally",
        child.text <-- browser.signal(clicks).map(_.toString),
      ),
    ),
  )

  override def popup() = Some(LaminarPage(browser =>
    div(
      p(
        cls := "clicks",
        child.text <-- browser.signal(clicks).map(clicks => s"$clicks clicks"),
      ),
      button(
        cls := "add",
        onClick --> browser.updater(clicks)((count, _) => count + 1),
      ),
      p(
        cls := "doubled",
        child.text <--
          browser.events(browser.runtime.call(double)(21)).map(_.toString),
      ),
    ),
  ))

  override def options() = Some(LaminarPage(browser =>
    div(
      input(
        cls := "enabled",
        typ := "checkbox",
        checked <-- browser.signal(enabled),
        onInput.mapToChecked --> browser.writer(enabled),
      ),
      input(
        cls := "note",
        value <-- browser.signal(note),
        onChange.mapToValue --> browser.writer(note),
      ),
      input(
        cls := "tag",
        onChange.mapToValue -->
          browser.updater(tags)((all, tag: String) => tag :: all),
      ),
    ),
  ))

/**
  * Copies each new value of `original` to `copy`, and counts its starts, while
  * its popup shows the copier, which a button there shows and hides.
  */
object Copier extends BrowserExtension[Storage]:

  override val name = "Copier"

  val original = StorageKey.local[Int]("original", 0)

  val copy = StorageKey.local[Int]("copy", 0)

  /** How many times the copier has started. */
  val starts = StorageKey.local[Int]("starts", 0)

  override def popup() = Some(LaminarPage(browser =>
    val shown = Var(true)
    div(
      button(
        cls := "toggle",
        onClick --> (_ => shown.update(!_)),
      ),
      child.maybe <-- shown.signal.map(Option.when(_)(copier(browser))),
    ),
  ))

  private def copier(browser: Browser): HtmlElement = span(
    browser.events(browser.storage.update(starts)(_ + 1)) --> (_ => ()),
    browser.events(
      browser.storage.changes(original).evalMap(browser.storage.set(copy, _)),
    ) --> (_ => ()),
  )

/**
  * Counts the presses of each secure page, which its content script shares, and
  * which the popup shows for the active tab.
  */
object Presser extends BrowserExtension[Any]:

  override val name = "Presser"

  /** How many times a page has been pressed. */
  val presses = Shared.tab[Int]("presses", 0)

  /** Presses a page. */
  val press = Endpoint.tab[Unit, Unit]("press")

  /** Stops a page sharing its presses. */
  val release = Endpoint.tab[Unit, Unit]("release")

  override def contentScripts() = Seq(ContentScript("https://*/*")(browser =>
    (SignallingRef[IO].of(0), Deferred[IO, Unit]).flatMapN((count, released) =>
      (browser.serve(press)(_ => count.update(_ + 1)) >>
        browser.serve(release)(_ => released.complete(()).void) >>
        browser.share(presses)(count)).surround(released.get),
    ),
  ))

  override def popup() = Some(LaminarPage(browser =>
    p(
      cls := "presses",
      child.text <--
        browser
          .events(browser.tabs.active)
          .collect { case Some(tab) => tab.id }
          .flatMapSwitch(browser.signal(presses, _))
          .map(presses => s"$presses presses"),
    ),
  ))
