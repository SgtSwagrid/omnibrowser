package com.alecdorrington.omnibrowser.testkit

import cats.effect.IO
import com.alecdorrington.omnibrowser.*
import com.alecdorrington.omnibrowser.Permission.Storage
import munit.CatsEffectSuite
import org.scalajs.dom
import org.scalajs.dom.document

class PagesSuite extends CatsEffectSuite:

  test(
    "A page begins with the body that the extension gives it, whose elements' handlers hear clicks and text entered, which becomes a field's value, but an element that it lacks cannot be acted on.",
  ):
    for
      browser <- FakeBrowser.install(Form)
      popup   <- browser.openPopup
      heading <- popup.text("h1")
      _       <- popup.click("button")
      signed  <- browser.stored(Form.signed)
      _       <- popup.enter("input", "Ada")
      value   <- popup.value("input")
      name    <- browser.stored(Form.guest)
      missing <- popup.click("textarea").attempt
    yield
      assertEquals(heading, "Sign the guestbook")
      assert(signed)
      assertEquals((value, name), ("Ada", "Ada"))
      assertEquals(
        missing,
        Left(NoSuchElement("textarea")),
      )

  test("A tab's text is its body's, as both its text and inner text."):
    for
      browser <- FakeBrowser.install(Form)
      page    <- browser.visit("https://example.com/", "Hello.")
      text    <- page.text
      inner   <- browser.stored(Form.seen)
    yield
      assertEquals(text, "Hello.")
      assertEquals(inner, "Hello.")

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
