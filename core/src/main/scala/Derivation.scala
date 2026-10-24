package com.alecdorrington.omnibrowser

import cats.syntax.all.*
import scala.scalajs.js

/**
  * The codecs of products and sums that [[Codec.derived]] makes, given the
  * codecs of their parts. Each part's codec is asked for only once it is
  * needed, so that a type may contain itself.
  */
object Derivation:

  /**
    * A codec of a product, whose fields are named `labels` and encoded by
    * `codecs`, and which `make` builds from its fields.
    */
  def product[A]
    (
      labels: List[String],
      codecs: => List[Codec[?]],
      make: Product => A,
    )
    : Codec[A] =
    lazy val fields = labels.zip(codecs)
    Codec.from[A](value => encodeFields(fields, value.asInstanceOf[Product]))(
      decodeFields(fields, _).map(make),
    )

  /**
    * A codec of a sum, whose cases are named `labels` and encoded by `codecs`,
    * and which `ordinal` tells apart.
    */
  def sum[A]
    (
      labels: List[String],
      codecs: => List[Codec[?]],
      ordinal: A => Int,
    )
    : Codec[A] =
    lazy val cases = labels.zip(codecs)
    Codec.from[A](value => encodeCase(cases(ordinal(value)), value))(
      decodeCase[A](cases, _),
    )

  private def encodeFields
    (
      fields: List[(String, Codec[?])],
      value: Product,
    )
    : js.Any = js.Dictionary(
    fields
      .zip(value.productIterator)
      .map((field, part) => field._1 -> encode(field._2, part))*,
  )

  private def decodeFields
    (
      fields: List[(String, Codec[?])],
      json: js.Any,
    )
    : Either[String, Product] = Codec
    .dictionary(json)
    .flatMap(found => fields.traverse(decodeField(found, _)))
    .map(parts => Tuple.fromArray(parts.toArray[Any]))

  private def decodeField
    (
      found: js.Dictionary[js.Any],
      field: (String, Codec[?]),
    )
    : Either[String, Any] = field
    ._2
    .decode(found.getOrElse(field._1, js.undefined))
    .leftMap(error => s"${ field._1 }: $error")

  /**
    * A case as its name if it has no fields, or else with its name under
    * `type`.
    */
  private def encodeCase(found: (String, Codec[?]), value: Any): js.Any =
    val fields = encode(found._2, value).asInstanceOf[js.Dictionary[js.Any]]
    if fields.isEmpty then found._1
    else js.Dictionary(("type" -> (found._1: js.Any)) +: fields.toSeq*)

  private def decodeCase[A]
    (
      cases: List[(String, Codec[?])],
      json: js.Any,
    )
    : Either[String, A] = name(json)
    .flatMap(name =>
      cases.find(_._1 == name).toRight(s"There is no case $name."),
    )
    .flatMap(_._2.decode(fieldsOf(json)))
    .map(_.asInstanceOf[A])

  /** The name of the case that `json` holds. */
  private def name(json: js.Any): Either[String, String] =
    if js.typeOf(json) == "string" then Right(json.asInstanceOf[String])
    else
      Codec
        .dictionary(json)
        .flatMap(
          _.get("type")
            .filter(js.typeOf(_) == "string")
            .toRight("Expected a case."),
        )
        .map(_.asInstanceOf[String])

  /** The fields of the case that `json` holds, of which a name alone has none. */
  private def fieldsOf(json: js.Any): js.Any =
    if js.typeOf(json) == "string" then js.Dictionary[js.Any]() else json

  private def encode(codec: Codec[?], value: Any): js.Any = codec
    .asInstanceOf[Codec[Any]]
    .encode(value)
