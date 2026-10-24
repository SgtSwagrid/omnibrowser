package com.alecdorrington.omnibrowser.example

import com.alecdorrington.omnibrowser.Target
import com.alecdorrington.omnibrowser.launch.{Build, Describe}
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

  test("The extension has a background, a content script and a popup."):
    assertEquals(
      files(Target.Chrome).keySet,
      Set(
        "manifest.json",
        "background.js",
        "content-1.js",
        "popup.html",
        "popup.js",
      ),
    )

  test("The extension requests only the storage permission."):
    val manifest = js.JSON.parse(files(Target.Firefox)("manifest.json"))
    assertEquals(
      manifest.permissions.asInstanceOf[js.Array[String]].toList,
      List("storage"),
    )

  test("Each bootstrap names the extension."):
    files(Target.Chrome)
      .removed("manifest.json")
      .removed("popup.html")
      .values
      .foreach(script => assert(script.contains(name)))
