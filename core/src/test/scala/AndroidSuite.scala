package com.alecdorrington.omnibrowser

import com.alecdorrington.omnibrowser.launch.{AndroidSupport, Build, Describe}
import munit.FunSuite
import scala.scalajs.js

class AndroidSuite extends FunSuite:

  private def settings(extension: BrowserExtension[?]): js.Dynamic = js
    .JSON
    .parse(
      Describe.files(
        extension,
        "Mobile",
        Build(Target.Firefox, "1", "id"),
      )("manifest.json"),
    )
    .browser_specific_settings

  test(
    "Firefox for Android may install the extension, from the version asked for, unless it is for desktop Firefox alone.",
  ):
    val android = settings(Mobile).gecko_android
    assert(!js.isUndefined(android))
    assert(js.isUndefined(android.strict_min_version))
    assertEquals(
      settings(Recent).gecko_android.strict_min_version.asInstanceOf[String],
      "120.0",
    )
    assert(js.isUndefined(settings(Desktop).gecko_android))

  test("Chromium and Safari are told nothing of Android."):
    Seq(Target.Chromium, Target.Safari).foreach: target =>
      val manifest = js
        .JSON
        .parse(
          Describe.files(
            Mobile,
            "Mobile",
            Build(target, "1", "id"),
          )("manifest.json"),
        )
      assert(js.isUndefined(manifest.browser_specific_settings))
      assertEquals(
        Describe.warnings(Mobile, Build(target, "1", "id")),
        Seq.empty,
      )

  test(
    "A permission or optional permission that Firefox for Android lacks is warned of.",
  ):
    val warnings =
      AndroidSupport.warnings(Set("bookmarks", "storage"), Set.empty)
    assertEquals(warnings.size, 1)
    assert(warnings.head.contains("permission bookmarks"))
    val optional = AndroidSupport.warnings(Set.empty, Set("history", "tabs"))
    assertEquals(optional.size, 1)
    assert(optional.head.contains("optional permission history"))

  test(
    "Nothing is warned of for the permissions that Android has, or for an extension that it does not run.",
  ):
    assertEquals(
      Describe.warnings(Mobile, Build(Target.Firefox, "1", "id")),
      Seq.empty,
    )
    assertEquals(
      AndroidSupport.warnings(Desktop),
      Seq.empty,
    )

/** An extension for both desktop Firefox and Firefox for Android. */
object Mobile extends BrowserExtension[Permission.Storage & Permission.Tabs]:
  override val name = "Mobile"

/** An extension for Firefox for Android from version 120 on. */
object Recent extends BrowserExtension[Any]:

  override val name    = "Recent"
  override val android = Android.Supported(Some("120.0"))

/** An extension for desktop Firefox alone. */
object Desktop extends BrowserExtension[Any]:

  override val name    = "Desktop"
  override val android = Android.Unsupported
