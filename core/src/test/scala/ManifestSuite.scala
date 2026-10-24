package com.alecdorrington.omnibrowser

import cats.effect.IO
import com.alecdorrington.omnibrowser.Permission.*
import com.alecdorrington.omnibrowser.launch.{Build, Describe, Manifest}
import munit.FunSuite
import scala.scalajs.js

class ManifestSuite extends FunSuite:

  private val name = "com.alecdorrington.omnibrowser.Sample"

  private def files(target: Target): Map[String, String] = Describe.files(
    Sample,
    name,
    Build(
      target,
      "1.2.3+4-abc",
      "sample@example.com",
    ),
  )

  private def manifest(target: Target): js.Dynamic = js
    .JSON
    .parse(files(target)("manifest.json"))

  private def strings(json: js.Dynamic): List[String] = json
    .asInstanceOf[js.Array[String]]
    .toList

  test("A release's version is its own."):
    assertEquals(
      Manifest.numericVersion("1.2.3"),
      "1.2.3",
    )
    assertEquals(Manifest.numericVersion("0.1"), "0.1")

  test("A development version counts the commits since its release."):
    assertEquals(
      Manifest.numericVersion("1.2.3+4-abc-SNAPSHOT"),
      "1.2.3.4",
    )
    assertEquals(
      Manifest.numericVersion("1.2.3.4+5-abc"),
      "1.2.3.4",
    )
    assertEquals(
      manifest(Target.Firefox).version.asInstanceOf[String],
      "1.2.3.4",
    )

  test("A version is never all zeros, which Chrome refuses."):
    assertEquals(
      Manifest.numericVersion("0.0.0+3-abc-SNAPSHOT"),
      "0.0.0.3",
    )
    assertEquals(
      Manifest.numericVersion("0.0.0"),
      "0.0.0.1",
    )
    assertEquals(
      Manifest.numericVersion("0.0.0.0"),
      "0.0.0.1",
    )
    assertEquals(
      Manifest.numericVersion("SNAPSHOT"),
      "0.1",
    )

  test("Chrome shows the build's full version beside the numeric one."):
    assertEquals(
      manifest(Target.Chrome).version_name.asInstanceOf[String],
      "1.2.3+4-abc",
    )

  test("The manifest requests the extension's permissions."):
    assertEquals(
      strings(manifest(Target.Chrome).permissions),
      List("storage"),
    )

  test("Chrome runs the background as a service worker that loads the bundle."):
    val background = manifest(Target.Chrome).background
    assertEquals(
      background.service_worker.asInstanceOf[String],
      "background.js",
    )
    assert(files(Target.Chrome)("background.js").contains("importScripts"))

  test("Firefox runs the background as a page that loads the bundle."):
    val background = manifest(Target.Firefox).background
    assertEquals(
      strings(background.scripts),
      List("background.js", "omnibrowser.js"),
    )
    assert(!files(Target.Firefox)("background.js").contains("importScripts"))

  test("Only Firefox is told the extension's ID."):
    assertEquals(
      manifest(Target.Firefox)
        .browser_specific_settings
        .gecko
        .id
        .asInstanceOf[String],
      "sample@example.com",
    )
    assert(js.isUndefined(manifest(Target.Chrome).browser_specific_settings))

  test(
    "Firefox is told that an extension collects nothing, unless it says otherwise.",
  ):
    val permissions = manifest(Target.Firefox)
      .browser_specific_settings
      .gecko
      .data_collection_permissions
    assertEquals(
      strings(permissions.required),
      List("none"),
    )
    assert(js.isUndefined(permissions.optional))

  test("Firefox is told what data an extension collects."):
    val permissions = js
      .JSON
      .parse(
        Describe.files(
          Collector,
          "Collector",
          Build(Target.Firefox, "1", "id"),
        )("manifest.json"),
      )
      .browser_specific_settings
      .gecko
      .data_collection_permissions
    assertEquals(
      strings(permissions.required),
      List("browsingActivity", "websiteContent"),
    )
    assertEquals(
      strings(permissions.optional),
      List("technicalAndInteraction"),
    )

  test("Each content script loads its bootstrap before the bundle."):
    val scripts = manifest(Target.Chrome)
      .content_scripts
      .asInstanceOf[js.Array[js.Dynamic]]
    assertEquals(scripts.length, 1)
    assertEquals(
      strings(scripts(0).js),
      List("content-1.js", "omnibrowser.js"),
    )
    assertEquals(
      strings(scripts(0).matches),
      List("https://*/*"),
    )
    assertEquals(
      scripts(0).run_at.asInstanceOf[String],
      "document_start",
    )

  test("The popup is a page that loads its bootstrap before the bundle."):
    assertEquals(
      manifest(Target.Chrome).action.default_popup.asInstanceOf[String],
      "popup.html",
    )
    val html = files(Target.Chrome)("popup.html")
    assert(html.indexOf("popup.js") < html.indexOf("omnibrowser.js"))
    assert(html.contains("<title>Sample &amp; Co</title>"))

  test("An extension without a background or options page declares neither."):
    val manifest = js
      .JSON
      .parse(
        Describe.files(
          Minimal,
          "Minimal",
          Build(Target.Chrome, "1", "id"),
        )("manifest.json"),
      )
    assert(js.isUndefined(manifest.background))
    assert(js.isUndefined(manifest.options_ui))
    assert(js.isUndefined(manifest.permissions))

/** An extension with one of every part. */
object Sample extends BrowserExtension[Storage]:

  override val name = "Sample & Co"

  override val background = Background(onStartup(_ => IO.unit))

  override val contentScripts =
    Seq(ContentScript("https://*/*")(_ => IO.unit).at(RunAt.Start))

  override val popup = Some(Page(_ => IO.unit))

/** An extension with no parts at all. */
object Minimal extends BrowserExtension[Any]:
  override val name = "Minimal"

/** An extension that collects data. */
object Collector extends BrowserExtension[Any]:

  override val name = "Collector"

  override val dataCollection = DataCollection(
    required = Set(
      PersonalData.WebsiteContent,
      PersonalData.BrowsingActivity,
    ),
    optional = Set(DataKind.TechnicalAndInteraction),
  )
