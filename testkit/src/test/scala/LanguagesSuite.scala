package com.alecdorrington.omnibrowser.testkit

import cats.effect.IO
import cats.syntax.all.*
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

  test(
    "The background and content scripts find the language of a text as the browser does, and none where it cannot tell.",
  ):
    for
      browser <- FakeBrowser.install(Linguist)
      unknown <- browser.call(Linguist.detect)("Hallo Welt")
      _       <- browser.detectLanguages(greeting)
      found   <- browser.call(Linguist.detect)("Hallo Welt")
      hallo   <- browser.visit("https://example.com/", "Hallo Welt")
      hello   <- browser.visit("https://example.org/", "Hello world")
      first   <- browser.badgeOf(hallo.tab.get)
      second  <- browser.badgeOf(hello.tab.get)
    yield
      assertEquals(
        (unknown, found),
        (Detection.none, german),
      )
      assertEquals((first, second), ("de", "?"))

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

  test(
    "A browser's detector gives the share of a text in each language as a whole percentage, or none where it is missing, as Safari's is, or fails, as Firefox for Android's did before 106.",
  ):
    val sharing = detecting(_ =>
      js.Promise
        .resolve(
          js.Dynamic
            .literal(
              isReliable = true,
              languages =
                js.Array(js.Dynamic.literal(language = "de", percentage = 79.6)),
            ),
        ),
    )
    List[(js.UndefOr[js.Object], Detection)](
      (js.Dynamic.literal(), Detection.none),
      (js.undefined, Detection.none),
      (
        detecting(_ => throw js.JavaScriptException(js.Error("No."))),
        Detection.none,
      ),
      (detecting(_ => js.Promise.reject(js.Error("No."))), Detection.none),
      (
        sharing,
        Detection(
          List(DetectedLanguage("de", 80)),
          reliable = true,
        ),
      ),
    ).traverse_((api, expected) => detect(api).assertEquals(expected))

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
