package com.alecdorrington.omnibrowser.megalingual

import com.alecdorrington.megalingual.Language
import io.circe.Codec

/**
  * A voice of the browser's, in which it says text aloud.
  *
  * @param id
  *   What tells it apart from the browser's other voices.
  *
  * @param name
  *   Its name, as the browser shows it.
  *
  * @param tag
  *   The BCP 47 tag of the language it speaks, as the browser gives it.
  *
  * @param local
  *   Whether it speaks on the device itself, rather than through an online
  *   service of the browser's maker, which is sent what it says.
  */
final case class Voice
  (
    id: String,
    name: String,
    tag: String,
    local: Boolean,
  )
  derives Codec:

  /** The language it speaks, if Megalingual knows its tag. */
  def language: Option[Language] = Language.fromTag(tag)

  /**
    * How far it is from how words of `language` are said, if it is of the same
    * tree of languages: how many steps apart in the tree its language is from
    * the variety that `language` is spoken in (see [[Voice.spoken]]), then
    * whether it is online, then its language's place among Megalingual's.
    */
  private def distance(language: Language): Option[(Int, Boolean, Int)] = this
    .language
    .filter(_.lineage.last == language.lineage.last)
    .map(own => (Voice.steps(own, Voice.spoken(language)), !local, own.ordinal))

object Voice:

  /**
    * The voice that best says words of a language: of its tree of languages,
    * the nearest to the variety the language is spoken in, as German is in
    * German German and Chinese in Simplified Chinese; then one on the device
    * before one online; then Megalingual's order, as Taiwanese Mandarin comes
    * before Cantonese for Simplified Chinese; then the first.
    *
    * @param language
    *   The language.
    *
    * @param voices
    *   The voices to choose among.
    *
    * @return
    *   The voice, if any is of the language's tree, as a voice of another
    *   language would read its words as its own.
    */
  def best(language: Language, voices: List[Voice]): Option[Voice] = voices
    .flatMap(voice => voice.distance(language).map(_ -> voice))
    .minByOption(_._1)
    .map(_._2)

  /**
    * The variety that words of a language are said in: its first, as
    * Megalingual reads a broader language, else itself.
    *
    * @param language
    *   The language.
    *
    * @return
    *   Its first variety, through each level, or itself if it has none.
    */
  def spoken(language: Language): Language = language
    .children
    .headOption
    .fold(language)(spoken)

  /**
    * How many steps apart `from` and `to` are in Megalingual's tree, up from
    * `from` to the nearest language that both are within, then down to `to`.
    */
  private def steps(from: Language, to: Language): Int =
    val up = from.lineage.indexWhere(to.within)
    if up < 0 then Int.MaxValue else up + to.lineage.indexOf(from.lineage(up))
