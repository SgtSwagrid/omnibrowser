package com.alecdorrington.omnibrowser.testkit

import cats.effect.IO
import com.alecdorrington.omnibrowser.*
import com.alecdorrington.omnibrowser.Permission.Storage
import munit.CatsEffectSuite
import org.scalajs.dom
import org.scalajs.dom.document

class PagesSuite extends CatsEffectSuite:

  test("A page begins with the body that the extension gives it."):
    FakeBrowser
      .install(Form)
      .flatMap(_.openPopup)
      .flatMap(_.text("h1"))
      .assertEquals("Sign the guestbook")

  test("A click on an element reaches its handlers."):
    for
      browser <- FakeBrowser.install(Form)
      popup   <- browser.openPopup
      _       <- popup.click("button")
      signed  <- browser.stored(Form.signed)
    yield assert(signed)

  test(
    "Text entered into a field becomes its value, of which its handlers hear.",
  ):
    for
      browser <- FakeBrowser.install(Form)
      popup   <- browser.openPopup
      _       <- popup.enter("input", "Ada")
      value   <- popup.value("input")
      name    <- browser.stored(Form.guest)
    yield
      assertEquals(value, "Ada")
      assertEquals(name, "Ada")

  test("A tab's text is its body's, as both its text and inner text."):
    for
      browser <- FakeBrowser.install(Form)
      page    <- browser.visit("https://example.com/", "Hello.")
      text    <- page.text
      inner   <- browser.stored(Form.seen)
    yield
      assertEquals(text, "Hello.")
      assertEquals(inner, "Hello.")

  test("An element that the page lacks cannot be acted on."):
    FakeBrowser
      .install(Form)
      .flatMap(_.openPopup)
      .flatMap(_.click("textarea").attempt)
      .map(result =>
        assertEquals(
          result,
          Left(NoSuchElement("textarea")),
        ),
      )

/** A guestbook, whose popup the user signs. */
object Form extends BrowserExtension[Storage]:

  override val name = "Form"

  val guest = StorageKey.local[String]("guest", "")

  val signed = StorageKey.local[Boolean]("signed", false)

  /** The inner text of the last page visited. */
  val seen = StorageKey.local[String]("seen", "")

  override def contentScripts() = Seq(ContentScript("https://*/*")(browser =>
    browser.storage.set(seen, document.body.innerText),
  ))

  override def popup() = Some(
    Page(browser =>
      IO:
        listen(browser, "button", "click")(_ =>
          browser.storage.set(signed, true),
        )
        listen(browser, "input", "change")(field =>
          browser
            .storage
            .set(
              guest,
              field.asInstanceOf[dom.html.Input].value,
            ),
        ),
    ).withBody("<h1>Sign the guestbook</h1><input><button>Sign</button>"),
  )

  /**
    * Has `browser` run the program that `run` makes of the element that
    * `selector` matches each time that it fires the event `name`.
    */
  private def listen
    (
      browser: Browser,
      selector: String,
      name: String,
    )
    (run: dom.Element => IO[Unit])
    : Unit =
    val element = document.querySelector(selector)
    element.addEventListener(
      name,
      (_: dom.Event) => browser.run(run(element)),
    )
