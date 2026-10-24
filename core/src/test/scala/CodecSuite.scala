package com.alecdorrington.omnibrowser

import munit.FunSuite
import scala.scalajs.js

class CodecSuite extends FunSuite:

  private def roundTrip[A : Codec as A](value: A): Unit =
    val json = js.JSON.parse(js.JSON.stringify(A.encode(value)))
    assertEquals(A.decode(json), Right(value))

  test("Primitives survive serialisation."):
    roundTrip("text")
    roundTrip(true)
    roundTrip(1.5)
    roundTrip(42)

  test("A `Long` beyond what a JSON number holds survives serialisation."):
    roundTrip(Long.MaxValue)

  test("Collections survive serialisation."):
    roundTrip(List(1, 2, 3))
    roundTrip(Map("a" -> List("x"), "b" -> Nil))
    roundTrip(Option(1))
    roundTrip(Option.empty[Int])

  test("A value of the wrong type is rejected."):
    assert(Codec[Int].decode("text").isLeft)
    assert(Codec[Int].decode(1.5).isLeft)
    assert(Codec[List[Int]].decode(js.Array("text")).isLeft)

class DerivedCodecSuite extends FunSuite:

  private def json[A : Codec as A](value: A): String = js
    .JSON
    .stringify(A.encode(value))

  private def roundTrip[A : Codec as A](value: A): Unit = assertEquals(
    A.decode(js.JSON.parse(json(value))),
    Right(value),
  )

  test("A case class is an object of its fields."):
    assertEquals(
      json(Word("chat", Some("le chat"))),
      """{"text":"chat","context":"le chat"}""",
    )
    roundTrip(Word("chat", Some("le chat")))

  test("A missing optional field is none."):
    assertEquals(
      Codec[Word].decode(js.JSON.parse("""{"text":"chat"}""")),
      Right(Word("chat", None)),
    )

  test("A missing field that is not optional says which it is."):
    assert(
      Codec[Word].decode(js.JSON.parse("{}")).left.exists(_.startsWith("text:")),
    )

  test("A case without fields is its name."):
    assertEquals(json(Colour.Red), "\"Red\"")
    roundTrip(Colour.Green)

  test("A case with fields has its name under `type`."):
    assertEquals(
      json(Shape.Circle(2)),
      """{"type":"Circle","radius":2}""",
    )
    roundTrip[Shape](Shape.Square(1.5))
    roundTrip[Shape](Shape.Dot)

  test("A sealed trait's cases are told apart by their names."):
    roundTrip[Answer](Answer.Yes)
    roundTrip[Answer](Answer.Maybe("likely"))

  test("An unknown case is rejected."):
    assertEquals(
      Codec[Colour].decode("Purple"),
      Left("There is no case Purple."),
    )

  test("A type may contain itself."):
    roundTrip(Tree(
      "root",
      List(
        Tree("leaf", Nil),
        Tree("branch", List(Tree("twig", Nil))),
      ),
    ))

  test("Vectors and sets survive serialisation."):
    roundTrip(Vector(1, 2, 3))
    roundTrip(Set("a", "b"))

final case class Word(text: String, context: Option[String]) derives Codec

enum Colour derives Codec:
  case Red, Green

enum Shape derives Codec:

  case Circle(radius: Int)
  case Square(side: Double)
  case Dot

sealed trait Answer derives Codec

object Answer:

  case object Yes                        extends Answer
  final case class Maybe(reason: String) extends Answer

final case class Tree(name: String, children: List[Tree]) derives Codec
