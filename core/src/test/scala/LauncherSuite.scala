package com.alecdorrington.omnibrowser

import cats.effect.IO
import com.alecdorrington.omnibrowser.launch.Launcher
import munit.FunSuite
import scala.concurrent.duration.*

class LauncherSuite extends FunSuite:

  test("A program runs at once, until its first asynchronous step."):
    var steps = List.empty[String]
    Launcher
      .default
      .launch(
        IO(steps :+= "first") >> IO.sleep(1.milli) >> IO(steps :+= "later"),
      )
    assertEquals(steps, List("first"))
