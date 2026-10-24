package com.alecdorrington.omnibrowser

import cats.syntax.all.*
import scala.scalajs.js

/**
  * A conversion between values of `A` and the JSON in which messages and
  * storage hold them.
  */
trait Codec[A]:

  /** The JSON that holds `value`. */
  def encode(value: A): js.Any

  /** The value that `json` holds, or why it holds none. */
  def decode(json: js.Any): Either[String, A]

  /**
    * A codec of `B` through this one, which may reject values of `A` that are
    * not values of `B`.
    */
  final def emap[B]
    (decoded: A => Either[String, B])
    (encoded: B => A)
    : Codec[B] =
    Codec.from[B](value => encode(encoded(value)))(decode(_).flatMap(decoded))

object Codec:

  /** The codec of `A`. */
  def apply[A](using codec: Codec[A]): Codec[A] = codec

  /** A codec of its two halves. */
  def from[A]
    (encoder: A => js.Any)
    (decoder: js.Any => Either[String, A])
    : Codec[A] = new:
    override def encode(value: A): js.Any                = encoder(value)
    override def decode(json: js.Any): Either[String, A] = decoder(json)

  given Codec[String] = primitive("string")

  given Codec[Boolean] = primitive("boolean")

  given Codec[Double] = primitive("number")

  given Codec[Int] = Codec[Double].emap(integer)(_.toDouble)

  /** As a string, as JSON numbers cannot hold every `Long`. */
  given Codec[Long] = Codec[String].emap(long)(_.toString)

  given Codec[Unit] = from[Unit](_ => null)(_ => Right(()))

  given [A : Codec as A] => Codec[Option[A]] = from[Option[A]](_.fold(null)(
    A.encode,
  ))(json => if isNull(json) then Right(None) else A.decode(json).map(Some(_)))

  given [A : Codec as A] => Codec[List[A]] = from[List[A]](values =>
    js.Array(values.map(A.encode)*),
  )(array(_).flatMap(_.toList.traverse(A.decode)))

  given [A : Codec as A] => Codec[Map[String, A]] = from[Map[String, A]](
    values => js.Dictionary(values.view.mapValues(A.encode).toSeq*),
  )(dictionary(_).flatMap(_.toList.traverse(decodeEntry[A]).map(_.toMap)))

  private def primitive[A](kind: String): Codec[A] =
    from[A](_.asInstanceOf[js.Any])(json =>
      Either.cond(
        js.typeOf(json) == kind,
        json.asInstanceOf[A],
        s"Expected a $kind.",
      ),
    )

  private def integer(value: Double): Either[String, Int] = Either.cond(
    value.isValidInt,
    value.toInt,
    s"Expected an integer, not $value.",
  )

  private def long(value: String): Either[String, Long] = value
    .toLongOption
    .toRight(s"Expected an integer, not $value.")

  private def decodeEntry[A : Codec as A]
    (entry: (String, js.Any))
    : Either[String, (String, A)] = A.decode(entry._2).map(entry._1 -> _)

  private def isNull(json: js.Any): Boolean = json == null ||
    js.isUndefined(json)

  private def array(json: js.Any): Either[String, js.Array[js.Any]] = Either
    .cond(
      js.Array.isArray(json),
      json.asInstanceOf[js.Array[js.Any]],
      "Expected an array.",
    )

  private def dictionary(json: js.Any): Either[String, js.Dictionary[js.Any]] =
    Either.cond(
      js.typeOf(json) == "object" && !isNull(json) && !js.Array.isArray(json),
      json.asInstanceOf[js.Dictionary[js.Any]],
      "Expected an object.",
    )
