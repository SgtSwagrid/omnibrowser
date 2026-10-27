package com.alecdorrington.omnibrowser

import com.alecdorrington.omnibrowser.webext.Circe.*
import io.circe.{Codec, Decoder, Encoder}
import munit.FunSuite
import scala.scalajs.js

class CirceSuite extends FunSuite:

  /** `value` as the browser holds it, after a round trip through JSON. */
  private def serialised[A : Encoder as A](value: A): js.Any = js
    .JSON
    .parse(js.JSON.stringify(A.encodeJs(value)))

  private def roundTrip[A : {Encoder, Decoder as A}](value: A): Unit =
    assertEquals(
      A.decodeJs(serialised(value)),
      Right(value),
    )

  test("Primitives survive serialisation."):
    roundTrip("text")
    roundTrip(true)
    roundTrip(1.5)
    roundTrip(42)
    roundTrip(())

  test("Collections survive serialisation."):
    roundTrip(List(1, 2, 3))
    roundTrip(Map("a" -> List("x"), "b" -> Nil))
    roundTrip(Option(1))
    roundTrip(Option.empty[Int])

  test("Derived values survive serialisation."):
    roundTrip(Word("chat", Some("le chat")))
    roundTrip[Shape](Shape.Circle(2))
    roundTrip[Shape](Shape.Dot)

  test("A missing optional field is none."):
    assertEquals(
      Decoder[Word].decodeJs(js.Dynamic.literal(text = "chat")),
      Right(Word("chat", None)),
    )

  test("A value of the wrong type is rejected, saying where."):
    assertEquals(
      Decoder[Word].decodeJs(js.Dynamic.literal(text = 1)),
      Left(".text: Got value '1' with wrong type, expecting string"),
    )

  test("A value from another realm is decoded."):
    val vm = js.Dynamic.global.process.getBuiltinModule("node:vm")
    assertEquals(
      Decoder[List[Word]].decodeJs(
        vm.runInNewContext("[{ text: 'chat', context: 'le chat' }]")
          .asInstanceOf[js.Any],
      ),
      Right(List(Word("chat", Some("le chat")))),
    )

  test("A value that JSON cannot hold is rejected."):
    assertEquals(
      Decoder[String].decodeJs(js.Symbol()),
      Left("Expected JSON, not a symbol."),
    )

final case class Word(text: String, context: Option[String]) derives Codec

enum Shape derives Codec:

  case Circle(radius: Int)
  case Dot
