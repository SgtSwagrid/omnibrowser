package com.alecdorrington.omnibrowser.megalingual

import cats.effect.IO
import cats.syntax.all.*
import com.alecdorrington.megalingual.Language
import com.alecdorrington.omnibrowser.*
import com.alecdorrington.omnibrowser.testkit.{FakeBrowser, FakeVoice, Said}
import munit.CatsEffectSuite
import scala.scalajs.js

class SpeechSuite extends CatsEffectSuite:

  /**
    * The ID of the voice, of those of `tags`, each on the device unless marked
    * `~` for online, that best says words of `language`, if any.
    */
  private def best(language: Language, tags: String*): Option[String] = Voice
    .best(
      language,
      tags
        .toList
        .map(tag =>
          Voice(
            tag,
            tag,
            tag.stripPrefix("~"),
            !tag.startsWith("~"),
          ),
        ),
    )
    .map(_.id)

  private val voices = List(
    FakeVoice("Hedda", "de-DE"),
    FakeVoice("Google Deutsch", "de-DE", local = false),
    FakeVoice("Zira", "en-US"),
  )

  test(
    "A word is said in the voice of its own variety, or else the nearest in Megalingual's tree, a broader language in its first variety.",
  ):
    assertEquals(
      best(
        Language.German,
        "en-US",
        "de-CH",
        "de-AT",
        "de-DE",
      ),
      Some("de-DE"),
    )
    assertEquals(
      best(
        Language.German,
        "en-US",
        "de-CH",
        "de-AT",
      ),
      Some("de-AT"),
    )
    assertEquals(
      best(
        Language.SwissStandardGerman,
        "de-DE",
        "de-CH",
      ),
      Some("de-CH"),
    )
    assertEquals(
      best(
        Language.English,
        "en-US",
        "en-AU",
        "en-GB",
      ),
      Some("en-GB"),
    )
    assertEquals(
      best(
        Language.SimplifiedChinese,
        "zh-HK",
        "zh-TW",
        "zh-CN",
      ),
      Some("zh-CN"),
    )
    assertEquals(
      best(
        Language.SimplifiedChinese,
        "zh-HK",
        "zh-TW",
      ),
      Some("zh-TW"),
    )
    assertEquals(
      best(Language.SwissGerman, "de-DE", "de-CH"),
      Some("de-CH"),
    )
    assertEquals(
      best(Language.Norwegian, "da-DK", "no-NO"),
      Some("no-NO"),
    )
    assertEquals(
      best(Language.Spanish, "es_MX"),
      Some("es_MX"),
    )

  test(
    "Of voices as near, one on the device is chosen before one online, else the first.",
  ):
    assertEquals(
      best(Language.German, "~de-DE", "de-DE"),
      Some("de-DE"),
    )
    assertEquals(
      best(Language.German, "~de-DE", "~de-de"),
      Some("~de-DE"),
    )
    assertEquals(
      best(Language.German, "de-AT", "~de-DE"),
      Some("~de-DE"),
    )

  test(
    "No voice says a word of another tree of languages, nor of a tag that names no language.",
  ):
    assertEquals(
      best(Language.French, "en-US", "de-DE"),
      None,
    )
    assertEquals(best(Language.German, "", "xx-YY"), None)
    assertEquals(best(Language.German), None)

  test(
    "The browser's voices are listed as it lists them, at once, or once it lists them late, as Chromium does, and none where it has none.",
  ):
    for
      none    <- FakeBrowser.install(Speaker)
      nothing <- none.call(Speaker.voices)(())
      soon    <- FakeBrowser.install(Speaker)
      _       <- soon.listVoices(voices)
      listed  <- soon.call(Speaker.voices)(())
      later   <- FakeBrowser.install(Speaker)
      _       <- later.listVoices(voices, late = true)
      late    <- later.call(Speaker.voices)(())
    yield
      assertEquals(nothing, Nil)
      assertEquals(
        listed,
        List(
          Voice("Hedda", "Hedda", "de-DE", local = true),
          Voice(
            "Google Deutsch",
            "Google Deutsch",
            "de-DE",
            local = false,
          ),
          Voice("Zira", "Zira", "en-US", local = true),
        ),
      )
      assertEquals(late, listed)
      assertEquals(
        listed.map(_.language),
        List(
          Some(Language.GermanGerman),
          Some(Language.GermanGerman),
          Some(Language.AmericanEnglish),
        ),
      )

  test(
    "Text is said in the browser's best voice for its language, cutting short what it was saying, and nothing where it has none.",
  ):
    for
      browser <- FakeBrowser.install(Speaker)
      _       <- browser.listVoices(voices, late = true)
      german  <- browser.call(Speaker.say)(("Hallo", Language.German))
      both    <- (
        browser.call(Speaker.say)(("Eins", Language.German)),
        browser.call(Speaker.say)(("Two", Language.English)),
      ).parTupled
      japanese <- browser.call(Speaker.say)(("こんにちは", Language.Japanese))
      said     <- browser.said
    yield
      assertEquals(german, None)
      assertEquals(both, (None, None))
      assertEquals(japanese, Some("no voice"))
      assertEquals(
        said.toSet,
        Set(
          Said("Hallo", "Hedda"),
          Said("Eins", "Hedda"),
          Said("Two", "Zira"),
        ),
      )
      assertEquals(said.head, Said("Hallo", "Hedda"))

  test(
    "Text cannot be said in a voice that the browser does not list, nor where it has no speech, as in a service worker.",
  ):
    val gone = Voice("Gone", "Gone", "de-DE", local = true)
    for
      browser <- FakeBrowser.install(Speaker)
      _       <- browser.listVoices(voices)
      failed  <- browser.call(Speaker.sayIn)(("Hallo", gone))
      said    <- browser.said
      silent  <- Speech(js.undefined).voices
      refused <- Speech(js.undefined).say("Hallo", gone).attempt
    yield
      assertEquals(
        failed,
        Some(SpeechError("no voice Gone").getMessage),
      )
      assertEquals(said, Nil)
      assertEquals(silent, Nil)
      assertEquals(
        refused,
        Left(SpeechError("no speech here")),
      )

/** Lists the browser's voices, and says text in them. */
object Speaker extends BrowserExtension[Any]:

  override val name = "Speaker"

  /** Gives the browser's voices. */
  val voices = Message[Unit, List[Voice]]("voices")

  /**
    * Says a text in the best voice for its language, giving why it could not,
    * if it could not.
    */
  val say = Message[(String, Language), Option[String]]("say")

  /** Says a text in a voice, giving why it could not, if it could not. */
  val sayIn = Message[(String, Voice), Option[String]]("sayIn")

  override def background() = Background(
    serve(voices)((_, browser) => browser.speech.voices),
    serve(say)((request, browser) =>
      browser
        .speech
        .voice(request._2)
        .flatMap(_.fold(IO.pure(Some("no voice")))(voice =>
          saying(browser, request._1, voice),
        )),
    ),
    serve(sayIn)((request, browser) => saying(browser, request._1, request._2)),
  )

  /** Says `text` in `voice`, giving why it could not, if it could not. */
  private def saying
    (
      browser: ContentBrowser,
      text: String,
      voice: Voice,
    )
    : IO[Option[String]] = browser
    .speech
    .say(text, voice)
    .as(Option.empty[String])
    .handleError(failure => Some(failure.getMessage))
