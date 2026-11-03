package com.alecdorrington.omnibrowser.testkit

import cats.effect.IO
import com.alecdorrington.omnibrowser.*
import munit.CatsEffectSuite
import scala.collection.mutable
import scala.scalajs.js

/**
  * The fake browser's speech, as code reading the global `speechSynthesis` sees
  * it, as Megalingual's `megalingual-web` does.
  */
class SpeechSuite extends CatsEffectSuite:

  private val voices = List(
    FakeVoice("Hedda", "de-DE"),
    FakeVoice("Google Deutsch", "de-DE", local = false),
  )

  /** The global `speechSynthesis`, the fake browser's last installed. */
  private def speech: js.Dynamic = js.Dynamic.global.globalThis.speechSynthesis

  /** Each voice that the speech lists: its name, tag and whether it is local. */
  private def listed: List[(String, String, Boolean)] = speech
    .getVoices()
    .asInstanceOf[js.Array[js.Dynamic]]
    .toList
    .map(voice =>
      (
        voice.name.asInstanceOf[String],
        voice.lang.asInstanceOf[String],
        voice.localService.asInstanceOf[Boolean],
      ),
    )

  private val expected = List(
    ("Hedda", "de-DE", true),
    ("Google Deutsch", "de-DE", false),
  )

  test(
    "The fake browser's speech lists the voices it is given, at once, or a moment after they are first asked for, then telling its listeners, and none until given any.",
  ):
    val relisted = mutable.ArrayBuffer.empty[List[(String, String, Boolean)]]
    for
      browser <- FakeBrowser.install(Mute)
      none    <- IO(listed)
      _       <- browser.listVoices(voices)
      soon    <- IO(listed)
      later   <- FakeBrowser.install(Mute)
      _       <- later.listVoices(voices, late = true)
      first   <- IO:
        speech.addEventListener(
          "voiceschanged",
          ((_: js.Any) => relisted += listed): js.Function1[js.Any, Unit],
        )
        listed
      _ <- later.settle
    yield
      assertEquals(none, Nil)
      assertEquals(soon, expected)
      assertEquals(first, Nil)
      assertEquals(relisted.toList, List(expected))

  test(
    "The fake browser says text by recording it, then telling its utterance that it began and ended, and cuts short what it was saying.",
  ):
    val told                                = mutable.ArrayBuffer.empty[String]
    def utterance(text: String): js.Dynamic =
      val utterance = js
        .Dynamic
        .newInstance(js.Dynamic.global.globalThis.SpeechSynthesisUtterance)(
          text,
        )
      utterance.voice = speech.getVoices().asInstanceOf[js.Array[js.Dynamic]](0)
      List("start", "end", "error").foreach(kind =>
        utterance.addEventListener(
          kind,
          (
            (event: js.Dynamic) =>
              told +=
                (if kind == "error" then s"$text error ${ event.error }"
                 else s"$text $kind"),
          ): js.Function1[js.Dynamic, Unit],
        ),
      )
      utterance
    for
      browser <- FakeBrowser.install(Mute)
      _       <- browser.listVoices(voices)
      _       <- IO:
        speech.speak(utterance("Eins"))
        speech.cancel()
        speech.speak(utterance("Zwei"))
      _    <- browser.settle
      said <- browser.said
    yield
      assertEquals(
        told.toList,
        List(
          "Eins error interrupted",
          "Zwei start",
          "Zwei end",
        ),
      )
      assertEquals(
        said,
        List(
          Said("Eins", "Hedda"),
          Said("Zwei", "Hedda"),
        ),
      )

/** An extension that does nothing, into whose fake browser a test speaks. */
object Mute extends BrowserExtension[Any]:

  override val name = "Mute"

  override def background() = Background()
