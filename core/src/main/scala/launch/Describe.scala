package com.alecdorrington.omnibrowser.launch

import com.alecdorrington.omnibrowser.{BrowserExtension, Target}
import scala.scalajs.js

/**
  * The files that make a bundle an extension for one browser, apart from the
  * bundle itself and the extension's assets.
  */
private[omnibrowser] object Describe:

  private val key = "__omnibrowserFiles"

  /** Gives the files describing `extension` to the build that ran the bundle. */
  def publish
    (
      extension: BrowserExtension[?],
      bootstrap: Bootstrap,
      build: Build,
    )
    : Unit = js
    .Dynamic
    .global
    .globalThis
    .updateDynamic(key)(js.Dictionary(
      files(extension, bootstrap.extension, build).toSeq*,
    ))

  /**
    * The files, by path, that make the bundle of `extension`, the object named
    * `name`, an extension for `build`'s target.
    */
  def files
    (
      extension: BrowserExtension[?],
      name: String,
      build: Build,
    )
    : Map[String, String] =
    Map(Layout.manifest -> Json.stringify(Manifest(extension, build))) ++
      backgroundFiles(extension, name, build.target) ++
      contentScriptFiles(extension, name) ++ pageFiles(extension, name)

  private def backgroundFiles
    (
      extension: BrowserExtension[?],
      name: String,
      target: Target,
    )
    : Map[String, String] =
    Map.from(Option.when(extension.background.handlers.nonEmpty)(
      Layout.background -> backgroundScript(name, target),
    ))

  /**
    * The background's bootstrap. Chrome's background is a service worker of a
    * single script, which must load the bundle itself, whereas Firefox loads
    * the bundle after it.
    */
  private def backgroundScript(name: String, target: Target): String =
    val bootstrap = Bootstrap.script(Bootstrap(name, Role.Background))
    target match
      case Target.Chrome => bootstrap +
          s"importScripts(\"${ Layout.bundle }\");\n"
      case Target.Firefox => bootstrap

  private def contentScriptFiles
    (
      extension: BrowserExtension[?],
      name: String,
    )
    : Map[String, String] = extension
    .contentScripts
    .indices
    .map(index =>
      Layout.contentScript(index) ->
        Bootstrap.script(Bootstrap(name, Role.ContentScript(index))),
    )
    .toMap

  private def pageFiles
    (
      extension: BrowserExtension[?],
      name: String,
    )
    : Map[String, String] = PageKind
    .values
    .toSeq
    .flatMap(kind => kind.of(extension).map(kind -> _))
    .flatMap((kind, page) =>
      Seq(
        Layout.page(kind)       -> Html.page(extension.name, kind, page),
        Layout.pageScript(kind) ->
          Bootstrap.script(Bootstrap(name, Role.Page(kind))),
      ),
    )
    .toMap
