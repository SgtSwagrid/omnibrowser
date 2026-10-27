package com.alecdorrington.omnibrowser.testkit

import cats.effect.IO
import com.alecdorrington.omnibrowser.*
import com.alecdorrington.omnibrowser.webext.{I18nApi, WebExtensionLanguages}
import munit.CatsEffectSuite
import org.scalajs.dom.document
import scala.scalajs.js

class LanguagesSuite extends CatsEffectSuite:

  private val german = Detection(
    List(DetectedLanguage("de", 100)),
    reliable = true,
  )

  /** Finds German in a text with a German greeting, and nothing in others. */
  private def greeting(text: String): Option[String] =
    Option.when(text.contains("Hallo"))("de")

  /** What a browser whose `i18n` is `api` finds in a German text. */
  private def detect(api: js.UndefOr[js.Object]): IO[Detection] =
    WebExtensionLanguages(api.asInstanceOf[js.UndefOr[I18nApi]]).detect(
      "Hallo Welt",
    )

  /** An `i18n` whose detector gives what `detector` does. */
  private def detecting(detector: js.Function1[String, js.Any]): js.Object = js
    .Dynamic
    .literal(detectLanguage = detector)

  test("The background finds the language of a text as the browser does."):
    for
      browser <- FakeBrowser.install(Linguist)
      _       <- browser.detectLanguages(greeting)
      found   <- browser.call(Linguist.detect)("Hallo Welt")
    yield assertEquals(found, german)

  test("A text that the browser cannot tell the language of is found in none."):
    FakeBrowser
      .install(Linguist)
      .flatMap(_.call(Linguist.detect)("Hallo Welt"))
      .assertEquals(Detection.none)

  test("A content script finds the language of its page."):
    for
      browser <- FakeBrowser.install(Linguist)
      _       <- browser.detectLanguages(greeting)
      german  <- browser.visit("https://example.com/", "Hallo Welt")
      english <- browser.visit("https://example.org/", "Hello world")
      first   <- browser.badgeOf(german.tab.get)
      second  <- browser.badgeOf(english.tab.get)
    yield assertEquals((first, second), ("de", "?"))

  test("A text's language is the one of the most of it, if found reliably."):
    val mixed = List(
      DetectedLanguage("de", 80),
      DetectedLanguage("en", 20),
    )
    assertEquals(
      Detection(mixed, reliable = true).language,
      Some("de"),
    )
    assertEquals(
      Detection(mixed, reliable = false).language,
      None,
    )
    assertEquals(Detection.none.language, None)

  test("A browser with no detector, as Safari, finds no language in any text."):
    detect(js.Dynamic.literal()).assertEquals(Detection.none) *>
      detect(js.undefined).assertEquals(Detection.none)

  test(
    "A detector that fails, as Firefox for Android's did before 106, finds none.",
  ):
    detect(detecting(_ => throw js.JavaScriptException(js.Error("No."))))
      .assertEquals(Detection.none) *> detect(
        detecting(_ => js.Promise.reject(js.Error("No."))),
      ).assertEquals(Detection.none)

  test("The share of a text in each language is a whole percentage."):
    detect(detecting(_ =>
      js.Promise
        .resolve(
          js.Dynamic
            .literal(
              isReliable = true,
              languages =
                js.Array(js.Dynamic.literal(language = "de", percentage = 79.6)),
            ),
        ),
    )).assertEquals(Detection(
      List(DetectedLanguage("de", 80)),
      reliable = true,
    ))

/** Finds the languages of texts, and of each page that it sees. */
object Linguist extends BrowserExtension[Any]:

  override val name = "Linguist"

  /** Gives the languages that the browser finds a text written in. */
  val detect = Message[String, Detection]("detect")

  /** Shows the language of the sender's page on its badge, or `?` if none. */
  val found = Message[Option[String], Unit]("found")

  override def background() = Background(
    serve(detect)((text, browser) => browser.languages.detect(text)),
    serve(found)((language, sender, browser) =>
      sender
        .tab
        .fold(IO.unit)(tab =>
          browser.action.setBadge(language.getOrElse("?"), tab.id),
        ),
    ),
  )

  override def contentScripts() = Seq(ContentScript("https://*/*")(browser =>
    IO(document.body.textContent)
      .flatMap(browser.languages.detect)
      .flatMap(found => browser.runtime.call(Linguist.found)(found.language)),
  ))
