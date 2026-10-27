package com.alecdorrington.omnibrowser.launch

import com.alecdorrington.omnibrowser.{
  Android, BrowserExtension, ContentScript, DataCollection, Target,
}
import scala.scalajs.js

/** The `manifest.json` of an extension, as each browser expects it. */
private[omnibrowser] object Manifest:

  def apply
    (
      extension: BrowserExtension[?],
      build: Build,
    )
    : js.Object = entries(
    entry("manifest_version", 3),
    entry("name", extension.name),
    entry(
      "version",
      numericVersion(build.version),
    ),
    entryIf(hasVersionName(build))("version_name", build.version),
    entryIf(extension.description.nonEmpty)(
      "description",
      extension.description,
    ),
    entryIf(extension.icons.nonEmpty)("icons", icons(extension)),
    entryIf(extension.permissions.nonEmpty)(
      "permissions",
      strings(extension.permissions.toSeq.sorted),
    ),
    entryIf(extension.hosts.nonEmpty)(
      "host_permissions",
      strings(extension.hosts),
    ),
    entryIf(extension.optionalPermissions.names.nonEmpty)(
      "optional_permissions",
      strings(extension.optionalPermissions.names.toSeq.sorted),
    ),
    entryIf(extension.optionalHosts.nonEmpty)(
      "optional_host_permissions",
      strings(extension.optionalHosts),
    ),
    entry("action", action(extension)),
    entryIf(extension.options().isDefined)("options_ui", optionsUi),
    entryIf(extension.background().handlers.nonEmpty)(
      "background",
      background(build.target),
    ),
    entryIf(extension.contentScripts().nonEmpty)(
      "content_scripts",
      contentScripts(extension),
    ),
    entryIf(build.target == Target.Firefox)(
      "browser_specific_settings",
      firefox(build.geckoId, extension),
    ),
  )

  /**
    * `version` as browsers accept it: up to four numbers, not all zero. A
    * development version, such as `1.2.3+4-abcdef12-SNAPSHOT` from
    * `sbt-dynver`, has the number of commits since its tag as a fourth, as in
    * `1.2.3.4`, so that it is newer than the release it follows.
    */
  def numericVersion(version: String): String = nonZero(numbers(version))
    .mkString(".")

  private val versionPattern = """(\d+(?:\.\d+){0,3})(?:\+(\d+))?""".r

  /** The numbers at the start of `version`, with any commit distance. */
  private def numbers(version: String): Seq[String] = versionPattern
    .findPrefixMatchOf(version)
    .fold(Seq("0"))(found =>
      val release = found.group(1).split('.').toSeq
      release ++ Option(found.group(2)).filter(_ => release.size < 4),
    )

  /** `numbers`, with a last `1` added if all are zero, as Chromium refuses. */
  private def nonZero(numbers: Seq[String]): Seq[String] =
    if numbers.exists(_.exists(_ != '0')) then numbers
    else numbers.take(3) :+ "1"

  /**
    * Whether to show the full version beside the numeric one, as every browser
    * but Firefox can.
    */
  private def hasVersionName(build: Build): Boolean = build.target !=
    Target.Firefox && numericVersion(build.version) != build.version

  private def action(extension: BrowserExtension[?]): js.Object = entries(
    entryIf(extension.popup().isDefined)(
      "default_popup",
      Layout.page(PageKind.Popup),
    ),
    entryIf(extension.icons.nonEmpty)("default_icon", icons(extension)),
  )

  private def optionsUi: js.Object = entries(
    entry("page", Layout.page(PageKind.Options)),
    entry("open_in_tab", false),
  )

  /**
    * Where the background is. Chromium and Safari run it as a service worker of
    * a single script, whereas Firefox runs it as a page of several scripts.
    * Safari on iOS requires a background that it may stop, as a service worker
    * is.
    */
  private def background(target: Target): js.Object = target match
    case Target.Chromium | Target.Safari =>
      entries(entry("service_worker", Layout.background))
    case Target.Firefox => entries(entry(
        "scripts",
        strings(Seq(Layout.background, Layout.bundle)),
      ))

  private def contentScripts
    (extension: BrowserExtension[?])
    : js.Array[js.Object] =
    js.Array(extension.contentScripts().zipWithIndex.map(contentScript)*)

  private def contentScript(script: ContentScript, index: Int): js.Object =
    entries(
      entry("matches", strings(script.matches)),
      entry(
        "js",
        strings(Seq(
          Layout.contentScript(index),
          Layout.contentBundle,
        )),
      ),
      entry("run_at", script.runAt.id),
      entryIf(script.css.nonEmpty)("css", strings(script.css)),
    )

  private def icons(extension: BrowserExtension[?]): js.Object = entries(
    extension
      .icons
      .toSeq
      .sorted
      .map((size, path) => entry(size.toString, path))*,
  )

  /**
    * What Firefox alone needs: the extension's ID, the data that it collects,
    * which Firefox shows the user on installing it, and whether Firefox for
    * Android may install it.
    */
  private def firefox
    (
      id: String,
      extension: BrowserExtension[?],
    )
    : js.Object = entries(
    entry(
      "gecko",
      entries(
        entry("id", id),
        entry(
          "data_collection_permissions",
          dataCollection(extension.dataCollection),
        ),
      ),
    ),
    android(extension.android).map("gecko_android" -> _),
  )

  /** What Firefox's add-on store reads to let Firefox for Android install it. */
  private def android(support: Android): Option[js.Object] = support match
    case Android.Supported(minimum) => Some(entries(
        minimum.map(version => "strict_min_version" -> (version: js.Any)),
      ))
    case Android.Unsupported => None

  private def dataCollection(collection: DataCollection): js.Object = entries(
    entry(
      "required",
      strings(required(collection)),
    ),
    entryIf(collection.optional.nonEmpty)(
      "optional",
      strings(collection.optional.map(_.id).toSeq.sorted),
    ),
  )

  /** The kinds of data required, or `none`, as Firefox asks for no kinds. */
  private def required(collection: DataCollection): Seq[String] =
    if collection.required.isEmpty then Seq("none")
    else collection.required.map(_.id).toSeq.sorted

  private def entries(fields: Option[(String, js.Any)]*): js.Object = js
    .Dictionary(fields.flatten*)
    .asInstanceOf[js.Object]

  private def entry(key: String, value: js.Any): Option[(String, js.Any)] =
    Some(key -> value)

  private def entryIf
    (condition: Boolean)
    (key: String, value: => js.Any)
    : Option[(String, js.Any)] = Option.when(condition)(key -> value)

  private def strings(values: Seq[String]): js.Array[String] = js.Array(values*)
