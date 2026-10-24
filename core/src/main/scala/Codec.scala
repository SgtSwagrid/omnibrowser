package com.alecdorrington.omnibrowser

import cats.syntax.all.*
import scala.compiletime.{constValueTuple, erasedValue, summonInline}
import scala.deriving.Mirror
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

  /**
    * A codec of `A` derived from its structure, as `derives Codec` asks. A case
    * class is an object of its fields. A case of an enum or sealed trait is its
    * name, if it has no fields, or else an object of its fields, with its name
    * under `type`.
    */
  inline def derived[A](using mirror: Mirror.Of[A]): Codec[A] =
    inline mirror match
      case product: Mirror.ProductOf[A] => Derivation.product[A](
          labels[product.MirroredElemLabels],
          fields[product.MirroredElemTypes],
          product.fromProduct(_),
        )
      case sum: Mirror.SumOf[A] => Derivation.sum[A](
          labels[sum.MirroredElemLabels],
          cases[sum.MirroredElemTypes],
          sum.ordinal(_),
        )

  private inline def labels[Labels <: Tuple]: List[String] =
    constValueTuple[Labels].toList.map(_.toString)

  /** The codecs of a product's fields, as they are given. */
  private inline def fields[Types <: Tuple]: List[Codec[?]] =
    inline erasedValue[Types] match
      case _: EmptyTuple     => Nil
      case _: (head *: tail) => summonInline[Codec[head]] :: fields[tail]

  /** The codecs of a sum's cases, each derived, so that each is an object. */
  private inline def cases[Types <: Tuple]: List[Codec[?]] =
    inline erasedValue[Types] match
      case _: EmptyTuple     => Nil
      case _: (head *: tail) =>
        derived[head](using summonInline[Mirror.Of[head]]) :: cases[tail]

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

  given [A : Codec] => Codec[Vector[A]] =
    Codec[List[A]].emap(values => Right(values.toVector))(_.toList)

  given [A : Codec] => Codec[Set[A]] =
    Codec[List[A]].emap(values => Right(values.toSet))(_.toList)

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

  private[omnibrowser] def isNull(json: js.Any): Boolean = json == null ||
    js.isUndefined(json)

  private def array(json: js.Any): Either[String, js.Array[js.Any]] = Either
    .cond(
      js.Array.isArray(json),
      json.asInstanceOf[js.Array[js.Any]],
      "Expected an array.",
    )

  private[omnibrowser] def dictionary
    (json: js.Any)
    : Either[String, js.Dictionary[js.Any]] = Either.cond(
    js.typeOf(json) == "object" && !isNull(json) && !js.Array.isArray(json),
    json.asInstanceOf[js.Dictionary[js.Any]],
    "Expected an object.",
  )
