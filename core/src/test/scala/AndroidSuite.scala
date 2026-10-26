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

  test("By default, Firefox for Android may install the extension."):
    val android = settings(Mobile).gecko_android
    assert(!js.isUndefined(android))
    assert(js.isUndefined(android.strict_min_version))

  test("The minimum version of Firefox for Android is given if asked for."):
    assertEquals(
      settings(Recent).gecko_android.strict_min_version.asInstanceOf[String],
      "120.0",
    )

  test("An extension for desktop Firefox alone does not mention Android."):
    assert(js.isUndefined(settings(Desktop).gecko_android))

  test("Chrome is told nothing of Android."):
    val manifest = js
      .JSON
      .parse(
        Describe.files(
          Mobile,
          "Mobile",
          Build(Target.Chrome, "1", "id"),
        )("manifest.json"),
      )
    assert(js.isUndefined(manifest.browser_specific_settings))

  test("A permission that Firefox for Android lacks is warned of."):
    val warnings =
      AndroidSupport.warnings(Set("bookmarks", "storage"), Set.empty)
    assertEquals(warnings.size, 1)
    assert(warnings.head.contains("permission bookmarks"))

  test("An optional permission that Firefox for Android lacks is warned of."):
    val warnings = AndroidSupport.warnings(Set.empty, Set("history", "tabs"))
    assertEquals(warnings.size, 1)
    assert(warnings.head.contains("optional permission history"))

  test("Nothing is warned of for an extension that Android does not run."):
    assertEquals(
      AndroidSupport.warnings(Desktop),
      Seq.empty,
    )

  test("Nothing is warned of for the permissions that Android has."):
    assertEquals(
      Describe.warnings(Mobile, Build(Target.Firefox, "1", "id")),
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
