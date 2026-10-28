package com.alecdorrington.omnibrowser.example

import com.alecdorrington.omnibrowser.Target
import com.alecdorrington.omnibrowser.launch.{Build, Describe}
import com.alecdorrington.omnibrowser.testkit.FakeBrowser
import munit.FunSuite
import scala.scalajs.js

class WordCountSuite extends FunSuite:

  private val name = "com.alecdorrington.omnibrowser.example.WordCount"

  private def files(target: Target): Map[String, String] = Describe.files(
    WordCount,
    name,
    Build(
      target,
      "0.1.0",
      "word-count@example.com",
    ),
  )

  test(
    "The extension has a background, a content script and a popup, each of whose bootstraps names it, and requests only the storage permission.",
  ):
    val manifest = js.JSON.parse(files(Target.Firefox)("manifest.json"))
    assertEquals(
      manifest.permissions.asInstanceOf[js.Array[String]].toList,
      List("storage"),
    )
    val all = files(Target.Chromium)
    assertEquals(
      all.keySet,
      Set(
        "manifest.json",
        "background.js",
        "content-1.js",
        "popup.html",
        "popup.js",
      ),
    )
    all
      .removed("manifest.json")
      .removed("popup.html")
      .values
      .foreach(script => assert(script.contains(name)))

  test("The content script's own bundle holds less than the main one."):
    val process   = js.Dynamic.global.process
    val directory = process.env.selectDynamic(FakeBrowser.buildVariable)
    val fs        = process.getBuiltinModule("fs")
    def size(bundle: String): Double = fs
      .statSync(s"$directory/${ Target.Chromium.id }/$bundle")
      .size
      .asInstanceOf[Double]
    assert(size("omnibrowser-content.js") < size("omnibrowser.js"))
