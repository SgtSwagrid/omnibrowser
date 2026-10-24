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
