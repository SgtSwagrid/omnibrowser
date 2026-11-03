package com.alecdorrington.omnibrowser.megalingual

import cats.effect.IO
import com.alecdorrington.megalingual.{Language, Languages}
import com.alecdorrington.omnibrowser.ContentBrowser
import org.scalajs.dom.{Event, EventTarget}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal

extension (browser: ContentBrowser)

  /** What the browser can say aloud, in its own voices. */
  def speech: Speech = Speech(Speech.synthesis)

/**
  * What the browser can say aloud, in its own voices, through the Web Speech
  * API, which needs no permission. Its voices read text as it is spelt. Pages
  * and content scripts have them, as does Firefox's background, but not a
  * service worker, as the background of Chromium and Safari is.
  */
final class Speech private[megalingual] (
  synthesis: js.UndefOr[SpeechSynthesisApi],
):

  /**
    * The browser's voices, once it has listed them: at once if it has, or else
    * once it does, as Chromium lists none until first asked, waiting at most
    * [[Speech.patience]].
    *
    * @return
    *   The voices, in the browser's order, none where it has no speech.
    */
  def voices: IO[List[Voice]] = synthesis.fold(IO.pure(Nil))(synthesis =>
    IO(Speech.listed(synthesis)).flatMap(listed =>
      if listed.nonEmpty then IO.pure(listed)
      else
        Speech
          .relisted(synthesis)
          .timeoutTo(Speech.patience, IO.unit)
          .flatMap(_ => IO(Speech.listed(synthesis))),
    ),
  )

  /**
    * The browser's voice that best says words of `language` (see
    * [[Voice.best]]).
    *
    * @param language
    *   The language.
    *
    * @return
    *   The voice, if the browser has one of the language's tree.
    */
  def voice(language: Language)(using Languages): IO[Option[Voice]] = voices
    .map(Voice.best(language, _))

  /**
    * Says `text` in `voice`, cutting short whatever the browser was saying,
    * even what the page itself was, as it says one thing at a time. It begins
    * in its first step, so that it still has the user's activation when run
    * from an event's handler (see [[ContentBrowser.run]]), which a browser may
    * need to speak. Cancelled, it stops saying it.
    *
    * @param text
    *   The text, read as it is spelt.
    *
    * @param voice
    *   One of the browser's [[voices]].
    *
    * @return
    *   Nothing, once the text has been said, or cut short by something else
    *   said. It fails with a [[SpeechError]] where the text cannot be said: the
    *   browser has no speech here, no longer has the voice, or the voice fails.
    */
  def say(text: String, voice: Voice): IO[Unit] = IO(start(text, voice))
    .flatMap(IO.fromEither)
    .flatMap(saying =>
      IO.fromPromiseCancelable(IO((saying.done, IO(saying.stop())))),
    )
    .flatMap(
      _.toOption.fold(IO.unit)(reason => IO.raiseError(SpeechError(reason))),
    )

  /** Begins saying `text` in `voice`, if the browser can. */
  private def start(text: String, voice: Voice): Either[SpeechError, Saying] =
    for
      synthesis <- synthesis.toOption.toRight(SpeechError("no speech here"))
      found     <- synthesis
        .getVoices()
        .find(_.voiceURI == voice.id)
        .toRight(SpeechError(s"no voice ${ voice.name }"))
    yield
      val utterance = SpeechSynthesisUtterance(text)
      utterance.voice = found
      utterance.lang = found.lang
      Saying.begin(synthesis, utterance)

object Speech:

  /**
    * How long to wait for the browser to list its voices, where it has listed
    * none when first asked.
    */
  val patience: FiniteDuration = 1.second

  /** The browser's speech, where this part has it. */
  private[megalingual] def synthesis: js.UndefOr[SpeechSynthesisApi] = js
    .Dynamic
    .global
    .globalThis
    .speechSynthesis
    .asInstanceOf[js.UndefOr[SpeechSynthesisApi]]

  /** The voices that `synthesis` lists now. */
  private[megalingual] def listed(synthesis: SpeechSynthesisApi): List[Voice] =
    synthesis
      .getVoices()
      .toList
      .map(voice =>
        Voice(
          voice.voiceURI,
          voice.name,
          voice.lang,
          voice.localService,
        ),
      )

  /** Nothing, once `synthesis` lists its voices again. */
  private[megalingual] def relisted(synthesis: SpeechSynthesisApi): IO[Unit] =
    IO.async[Unit](done =>
      IO:
        lazy val listener: js.Function1[Event, Unit] = _ =>
          synthesis.removeEventListener("voiceschanged", listener)
          done(Right(()))
        synthesis.addEventListener("voiceschanged", listener)
        Some(IO(synthesis.removeEventListener("voiceschanged", listener))),
    )

/**
  * What the browser is saying, an `utterance` of `synthesis`, which is `done`
  * once it has been said, or cut short, giving why it failed, if it did.
  */
private[megalingual] final class Saying private (
  synthesis: SpeechSynthesisApi,
  utterance: SpeechSynthesisUtterance,
):

  /** Whether it is still being said, or waiting to be. */
  private var saying = true

  val done: js.Promise[js.UndefOr[String]] = js.Promise((resolve, _) =>
    def end(failure: js.UndefOr[String]): Unit = if saying then
      saying = false
      resolve(failure)
    utterance.addEventListener("end", (_: Event) => end(js.undefined))
    utterance.addEventListener(
      "error",
      (event: Event) => end(Saying.failure(event)),
    ),
  )

  /**
    * Stops saying it, if it is still being said and nothing was begun after it:
    * cancelling cuts short whatever the browser is saying, and one cut short
    * still counts as being said until the browser tells it so, a moment after
    * what cut it short began.
    */
  def stop(): Unit =
    if saying && Saying.latest.exists(_ eq this) then synthesis.cancel()

private[megalingual] object Saying:

  /** What was begun last, which alone may cut the browser's speech short. */
  private var latest = Option.empty[Saying]

  /**
    * Begins saying `utterance`, cutting short whatever `synthesis` was saying,
    * all at once, so that it still has the user's activation.
    */
  def begin
    (
      synthesis: SpeechSynthesisApi,
      utterance: SpeechSynthesisUtterance,
    )
    : Saying =
    val saying = new Saying(synthesis, utterance)
    latest = Some(saying)
    synthesis.cancel()
    synthesis.speak(utterance)
    saying

  /**
    * Why an utterance failed, as its `error` event says, if it did: not if it
    * was only cut short, as by another.
    */
  def failure(event: Event): js.UndefOr[String] =
    val error = event.asInstanceOf[SpeechSynthesisErrorEventApi].error
    if Set("interrupted", "canceled")(error) then js.undefined else error

/** Why the browser could not say something, its `reason`. */
final case class SpeechError(reason: String)
  extends Exception(s"The browser could not say it: $reason.")

/** The browser's speech, as the Web Speech API gives it. */
@js.native
private[megalingual] trait SpeechSynthesisApi extends EventTarget:

  def getVoices(): js.Array[SpeechSynthesisVoiceApi] = js.native

  def speak(utterance: SpeechSynthesisUtterance): Unit = js.native

  def cancel(): Unit = js.native

@js.native
private[megalingual] trait SpeechSynthesisVoiceApi extends js.Object:

  val voiceURI: String      = js.native
  val name: String          = js.native
  val lang: String          = js.native
  val localService: Boolean = js.native

@js.native
private[megalingual] trait SpeechSynthesisErrorEventApi extends Event:

  val error: String = js.native

/** Text to be said aloud, in a `voice` and `lang`. */
@js.native
@JSGlobal
private[megalingual] class SpeechSynthesisUtterance
  (text: String)
  extends EventTarget:

  var voice: SpeechSynthesisVoiceApi = js.native

  var lang: String = js.native
