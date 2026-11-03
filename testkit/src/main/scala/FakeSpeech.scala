package com.alecdorrington.omnibrowser.testkit

import scala.collection.mutable
import scala.scalajs.js

/**
  * A voice that the fake browser has for speech: its `name`, which is also its
  * ID, the BCP 47 `tag` of its language, and whether it is `local`, on the
  * device, rather than online.
  */
final case class FakeVoice
  (
    name: String,
    tag: String,
    local: Boolean = true,
  )

/** What the fake browser said: its `text`, in the voice of the name `voice`. */
final case class Said(text: String, voice: String)

/**
  * What the fake browser can say aloud, and has said, which every part's speech
  * shares, each step of speaking taken a moment later through `later`.
  */
private[testkit] final class Speaking(later: (=> Unit) => Unit):

  private var voices = List.empty[FakeVoice]

  /** Whether the voices are listed only a moment after first being asked for. */
  private var late = false

  /** Whether the voices have been listed, if they are listed late. */
  private var ready = true

  /** Whether the voices have been asked for, if they are listed late. */
  private var asked = false

  /** What the browser has said, in order. */
  val said = mutable.ArrayBuffer.empty[Said]

  /** The browser's speech, as each part sees it. */
  val speech: FakeSpeech = FakeSpeech(this)

  /** Lists `voices`, only a moment after they are first asked for if `late`. */
  def list(voices: List[FakeVoice], late: Boolean): Unit =
    this.voices = voices
    this.late = late
    ready = !late
    asked = false

  /**
    * The voices, as `getVoices` gives them, none until a moment after they are
    * first asked for if they are listed late, and then `relist`ing them.
    */
  def listed(relist: () => Unit): js.Array[js.Object] =
    if ready then js.Array(voices.map(Speaking.voice)*)
    else
      if !asked then
        asked = true
        later:
          ready = true
          relist()
      js.Array()

  /** Takes the next step of speaking, a moment later. */
  def step(action: => Unit): Unit = later(action)

private[testkit] object Speaking:

  /** `voice`, as `getVoices` gives it. */
  private def voice(voice: FakeVoice): js.Object = js
    .Dynamic
    .literal(
      voiceURI = voice.name,
      name = voice.name,
      lang = voice.tag,
      localService = voice.local,
      default = false,
    )

/**
  * The browser's `speechSynthesis`, which lists the voices that `speaking` has,
  * and says text by recording it, then telling its utterance that it has begun
  * and ended, each a moment later. It says one thing at a time, cutting short
  * what it was saying.
  */
private[testkit] final class FakeSpeech(speaking: Speaking) extends js.Object:

  private val listeners = mutable.ArrayBuffer.empty[js.Function1[js.Any, Unit]]

  private var current = Option.empty[FakeUtterance]

  def getVoices(): js.Array[js.Object] = speaking.listed(() =>
    listeners.toList.foreach(_(js.Dynamic.literal(`type` = "voiceschanged"))),
  )

  def speak(utterance: FakeUtterance): Unit =
    speaking.said += Said(utterance.text, utterance.voiceName)
    current = Some(utterance)
    speaking.step(utterance.begin())
    speaking.step(utterance.finish())

  def cancel(): Unit =
    current.foreach(_.interrupt())
    current = None

  def addEventListener
    (
      kind: String,
      listener: js.Function1[js.Any, Unit],
    )
    : Unit = if kind == "voiceschanged" then listeners += listener

  def removeEventListener
    (
      kind: String,
      listener: js.Function1[js.Any, Unit],
    )
    : Unit = listeners -= listener

/**
  * The browser's `SpeechSynthesisUtterance`: its `text`, to be said in its
  * `voice`, which tells its listeners when it begins, and when it ends, or is
  * cut short.
  */
private[testkit] final class FakeUtterance(val text: String) extends js.Object:

  var voice: js.Any = null

  var lang: String = ""

  private val listeners = mutable
    .Map
    .empty[
      String,
      List[js.Function1[js.Any, Unit]],
    ]

  /** Whether it has been said, or cut short. */
  private var over = false

  def addEventListener
    (
      kind: String,
      listener: js.Function1[js.Any, Unit],
    )
    : Unit = listeners(kind) = listeners.getOrElse(kind, Nil) :+ listener

  def removeEventListener
    (
      kind: String,
      listener: js.Function1[js.Any, Unit],
    )
    : Unit =
    listeners(kind) = listeners.getOrElse(kind, Nil).filterNot(_ eq listener)

  /** The name of its voice, if it has one. */
  private[testkit] final def voiceName: String =
    if voice == null then ""
    else voice.asInstanceOf[js.Dynamic].name.asInstanceOf[String]

  /** Tells its listeners that it has begun, unless it is over. */
  private[testkit] final def begin(): Unit = if !over then
    tell(
      "start",
      js.Dynamic.literal(`type` = "start"),
    )

  /** Tells its listeners that it has ended, unless it was cut short. */
  private[testkit] final def finish(): Unit = if !over then
    over = true
    tell(
      "end",
      js.Dynamic.literal(`type` = "end"),
    )

  /** Tells its listeners that it was cut short, unless it is over. */
  private[testkit] final def interrupt(): Unit = if !over then
    over = true
    tell(
      "error",
      js.Dynamic.literal(`type` = "error", error = "interrupted"),
    )

  private def tell(kind: String, event: js.Any): Unit = listeners
    .getOrElse(kind, Nil)
    .foreach(_(event))
