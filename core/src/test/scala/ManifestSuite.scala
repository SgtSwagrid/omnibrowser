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

  test("A version is never all zeros, which Chromium refuses."):
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

  test(
    "Every browser but Firefox shows the build's full version beside the numeric one.",
  ):
    Seq(Target.Chromium, Target.Safari).foreach(target =>
      assertEquals(
        manifest(target).version_name.asInstanceOf[String],
        "1.2.3+4-abc",
      ),
    )
    assert(js.isUndefined(manifest(Target.Firefox).version_name))

  test("The manifest requests the extension's permissions."):
    assertEquals(
      strings(manifest(Target.Chromium).permissions),
      List("storage"),
    )

  test(
    "Chromium and Safari run the background as a service worker that loads the bundle.",
  ):
    Seq(Target.Chromium, Target.Safari).foreach: target =>
      val background = manifest(target).background
      assertEquals(
        background.service_worker.asInstanceOf[String],
        "background.js",
      )
      assert(js.isUndefined(background.scripts))
      assert(files(target)("background.js").contains("importScripts"))

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
    Seq(Target.Chromium, Target.Safari).foreach(target =>
      assert(js.isUndefined(manifest(target).browser_specific_settings)),
    )

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

  test("The manifest requests the hosts and optional permissions."):
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
      strings(scripts(0).matches),
      List("https://*/*"),
    )
    assertEquals(
      scripts(0).run_at.asInstanceOf[String],
      "document_start",
    )

  test("A content script's stylesheets are injected with it."):
    val scripts = manifest(Target.Firefox)
      .content_scripts
      .asInstanceOf[js.Array[js.Dynamic]]
    assertEquals(
      strings(scripts(0).css),
      List("content.css"),
    )

  test("A page's body and stylesheets come before its scripts."):
    val html = files(Target.Chromium)("popup.html")
    assert(html.contains("""<link rel="stylesheet" href="popup.css">"""))
    assert(html.indexOf("popup.css") < html.indexOf("</head>"))
    assert(html.indexOf("""<main id="app">""") < html.indexOf("popup.js"))

  test("The popup is a page that loads its bootstrap before the bundle."):
    assertEquals(
      manifest(Target.Chromium).action.default_popup.asInstanceOf[String],
      "popup.html",
    )
    val html = files(Target.Chromium)("popup.html")
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
