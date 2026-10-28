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

  test(
    "A release's version is its own, a development version counts the commits since its release, and none is all zeros, which Chromium refuses.",
  ):
    Seq(
      "1.2.3"                -> "1.2.3",
      "0.1"                  -> "0.1",
      "1.2.3+4-abc-SNAPSHOT" -> "1.2.3.4",
      "1.2.3.4+5-abc"        -> "1.2.3.4",
      "0.0.0+3-abc-SNAPSHOT" -> "0.0.0.3",
      "0.0.0"                -> "0.0.0.1",
      "0.0.0.0"              -> "0.0.0.1",
      "SNAPSHOT"             -> "0.1",
    ).foreach((version, numeric) =>
      assertEquals(
        Manifest.numericVersion(version),
        numeric,
        version,
      ),
    )

  test(
    "Every browser is given the numeric version, and every browser but Firefox the build's full version beside it.",
  ):
    Target
      .values
      .foreach(target =>
        assertEquals(
          manifest(target).version.asInstanceOf[String],
          "1.2.3.4",
        ),
      )
    Seq(Target.Chromium, Target.Safari).foreach(target =>
      assertEquals(
        manifest(target).version_name.asInstanceOf[String],
        "1.2.3+4-abc",
      ),
    )
    assert(js.isUndefined(manifest(Target.Firefox).version_name))

  test(
    "Chromium and Safari run the background as a service worker that loads the bundle, and Firefox as a page that does.",
  ):
    Seq(Target.Chromium, Target.Safari).foreach: target =>
      val background = manifest(target).background
      assertEquals(
        background.service_worker.asInstanceOf[String],
        "background.js",
      )
      assert(js.isUndefined(background.scripts))
      assert(files(target)("background.js").contains("importScripts"))
    val background = manifest(Target.Firefox).background
    assertEquals(
      strings(background.scripts),
      List("background.js", "omnibrowser.js"),
    )
    assert(!files(Target.Firefox)("background.js").contains("importScripts"))

  test(
    "Only Firefox is told the extension's ID and what data it collects, which is nothing unless it says otherwise.",
  ):
    val gecko = manifest(Target.Firefox).browser_specific_settings.gecko
    assertEquals(
      gecko.id.asInstanceOf[String],
      "sample@example.com",
    )
    assertEquals(
      strings(gecko.data_collection_permissions.required),
      List("none"),
    )
    assert(js.isUndefined(gecko.data_collection_permissions.optional))
    val collected = js
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
      strings(collected.required),
      List("browsingActivity", "websiteContent"),
    )
    assertEquals(
      strings(collected.optional),
      List("technicalAndInteraction"),
    )
    Seq(Target.Chromium, Target.Safari).foreach(target =>
      assert(js.isUndefined(manifest(target).browser_specific_settings)),
    )

  test(
    "The manifest requests the permissions, hosts and optional permissions.",
  ):
    val manifest = js
      .JSON
      .parse(
        Describe.files(
          Asker,
          "Asker",
          Build(Target.Chromium, "1", "id"),
        )("manifest.json"),
      )
    assertEquals(
      strings(manifest.permissions),
      List("storage"),
    )
    assertEquals(
      strings(manifest.host_permissions),
      List("https://api.example.com/*"),
    )
    assertEquals(
      strings(manifest.optional_permissions),
      List("activeTab", "tabs"),
    )
    assertEquals(
      strings(manifest.optional_host_permissions),
      List("https://*/*"),
    )

  test(
    "Each content script loads its bootstrap before the bundle, and its stylesheets with it.",
  ):
    val scripts = manifest(Target.Chromium)
      .content_scripts
      .asInstanceOf[js.Array[js.Dynamic]]
    assertEquals(scripts.length, 1)
    assertEquals(
      strings(scripts(0).js),
      List(
        "content-1.js",
        "omnibrowser-content.js",
      ),
    )
    assertEquals(
      strings(scripts(0).css),
      List("content.css"),
    )
    assertEquals(
      strings(scripts(0).matches),
      List("https://*/*"),
    )
    assertEquals(
      scripts(0).run_at.asInstanceOf[String],
      "document_start",
    )

  test(
    "The popup is a page whose body and stylesheets come before its bootstrap, which comes before the bundle.",
  ):
    assertEquals(
      manifest(Target.Chromium).action.default_popup.asInstanceOf[String],
      "popup.html",
    )
    val html = files(Target.Chromium)("popup.html")
    assert(html.contains("""<link rel="stylesheet" href="popup.css">"""))
    assert(html.indexOf("popup.css") < html.indexOf("</head>"))
    assert(html.indexOf("""<main id="app">""") < html.indexOf("popup.js"))
    assert(html.indexOf("popup.js") < html.indexOf("omnibrowser.js"))
    assert(html.contains("<title>Sample &amp; Co</title>"))

  test("An extension without a background or options page declares neither."):
    val manifest = js
      .JSON
      .parse(
        Describe.files(
          Minimal,
          "Minimal",
          Build(Target.Chromium, "1", "id"),
        )("manifest.json"),
      )
    assert(js.isUndefined(manifest.background))
    assert(js.isUndefined(manifest.options_ui))
    assert(js.isUndefined(manifest.permissions))

/** An extension with one of every part. */
object Sample extends BrowserExtension[Storage]:

  override val name = "Sample & Co"

  override def background() = Background(onStartup(_ => IO.unit))

  override def contentScripts() = Seq(
    ContentScript("https://*/*")(_ => IO.unit)
      .at(RunAt.Start)
      .withCss("content.css"),
  )

  override def popup() = Some(
    Page(_ => IO.unit)
      .withBody("""<main id="app"></main>""")
      .withStylesheets("popup.css"),
  )

/** An extension with no parts at all. */
object Minimal extends BrowserExtension[Any]:
  override val name = "Minimal"

/** An extension that asks for permissions while it runs. */
object Asker extends BrowserExtension[Storage]:

  override val name = "Asker"

  override val hosts = Seq("https://api.example.com/*")

  override val optionalPermissions = Permissions.of[Tabs & ActiveTab]

  override val optionalHosts = Seq("https://*/*")

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
