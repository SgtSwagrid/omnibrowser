package com.alecdorrington.omnibrowser.megalingual

import cats.effect.IO
import com.alecdorrington.megalingual.Language
import com.alecdorrington.omnibrowser.ContentBrowser
import com.alecdorrington.omnibrowser.webext.{I18nApi, LanguageDetectionApi}
import com.alecdorrington.omnibrowser.webext.Interop.promise
import io.circe.Codec
import scala.scalajs.js

extension (browser: ContentBrowser)

  /** What the browser can tell of the languages that text is written in. */
  def languages: Languages = Languages(browser.api.i18n)

/**
  * What the browser can tell of the languages that text is written in, through
  * its own detector, on the device, which needs no permission. Every part of
  * the extension has it, but in Safari, which has no detector.
  */
final class Languages private[megalingual] (api: js.UndefOr[I18nApi]):

  /**
    * The languages that `text` is written in, as the browser's detector finds
    * them. A short text, as a heading or a link, may be found in none, or not
    * reliably. Safari has no detector, nor Firefox for Android before 106,
    * where it always threw, so finds none in any text.
    *
    * @param text
    *   The text.
    *
    * @return
    *   The languages that the browser found, none where it has no detector or
    *   it failed.
    */
  def detect(text: String): IO[Detection] = api
    .filter(Languages.detects)
    .fold(IO.pure(Detection.none))(i18n =>
      promise(i18n.detectLanguage(text)).map(Languages.detection),
    )
    .handleError(_ => Detection.none)

private[megalingual] object Languages:

  /** Whether the browser's `i18n` has a detector of languages. */
  private def detects(i18n: I18nApi): Boolean =
    js.typeOf(i18n.asInstanceOf[js.Dynamic].detectLanguage) == "function"

  /** The detection that `found` gives. */
  def detection(found: LanguageDetectionApi): Detection = Detection(
    found
      .languages
      .toList
      .map(language =>
        DetectedLanguage(
          language.language,
          language.percentage.round.toInt,
        ),
      ),
    found.isReliable,
  )

/**
  * The languages that the browser found a text to be written in, the one of the
  * most of it first, which are only a guess unless the browser found them
  * `reliable`.
  */
final case class Detection
  (
    languages: List[DetectedLanguage],
    reliable: Boolean,
  )
  derives Codec:

  /**
    * The language of the most of the text, if the browser found it reliably and
    * Megalingual knows it.
    */
  def language: Option[Language] = languages
    .headOption
    .filter(_ => reliable)
    .flatMap(_.language)

object Detection:

  /** That the browser found no language in a text. */
  val none: Detection = Detection(Nil, reliable = false)

/**
  * A language that the browser found in a text, by the `code` that it gives it
  * (ISO 639, as `de`, or `zh-TW` where the region tells it apart), with the
  * `percentage` of the text written in it.
  */
final case class DetectedLanguage(code: String, percentage: Int) derives Codec:

  /** The language, if Megalingual knows its code. */
  def language: Option[Language] = Language.fromTag(code)
