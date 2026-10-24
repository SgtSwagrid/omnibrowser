package com.alecdorrington.omnibrowser.launch

import com.alecdorrington.omnibrowser.{BrowserExtension, Page, Target}
import com.alecdorrington.omnibrowser.webext.{
  WebExtensionApi, WebExtensionBrowser,
}
import scala.scalajs.js
import scala.scalajs.reflect.Reflect

/** A part of an extension that its bundle may start. */
private[omnibrowser] enum Role:

  case Background

  case ContentScript(index: Int)

  case Page(kind: PageKind)

  /** Describes the extension to the build, rather than starting any part. */
  case Describe(build: Build)

/** A page of an extension. */
private[omnibrowser] enum PageKind(val id: String):

  case Popup extends PageKind("popup")

  case Options extends PageKind("options")

  /** The page of this kind in `extension`, if it has one. */
  def of(extension: BrowserExtension[?]): Option[Page] = this match
    case Popup   => extension.popup
    case Options => extension.options

/** The target and version of a build of an extension. */
private[omnibrowser] final case class Build
  (
    target: Target,
    version: String,
    geckoId: String,
  )

/**
  * What a bundle is to do, which a script loaded before the bundle says, as it
  * names `extension`, the object defining the extension, and the `role` that it
  * is to play.
  */
private[omnibrowser] final case class Bootstrap(extension: String, role: Role)

private[omnibrowser] object Bootstrap:

  private val key = "__omnibrowser"

  /** What this bundle is to do, if a bootstrap said. */
  def current: Option[Bootstrap] =
    val fields = js.Dynamic.global.globalThis.selectDynamic(key)
    Option.unless(js.isUndefined(fields))(fields).flatMap(parse)

  /** A script, loaded before the bundle, saying what it is to do. */
  def script(bootstrap: Bootstrap): String =
    s"globalThis.$key = ${ Json.stringify(fields(bootstrap)) };\n"

  /** Starts the part of the extension that `bootstrap` names. */
  def start(bootstrap: Bootstrap): Unit =
    val extension    = load(bootstrap.extension)
    lazy val api     = WebExtensionApi.current
    lazy val browser = WebExtensionBrowser(api, Launcher.default.launch)
    bootstrap.role match
      case Role.Background =>
        Launcher.default.background(extension, browser, api)
      case Role.ContentScript(index) => extension
          .contentScripts
          .lift(index)
          .foreach(Launcher.default.contentScript(_, browser))
      case Role.Page(kind) =>
        kind.of(extension).foreach(Launcher.default.page(_, browser))
      case Role.Describe(build) => Describe.publish(extension, bootstrap, build)

  private def load(name: String): BrowserExtension[?] = Reflect
    .lookupLoadableModuleClass(s"$name$$")
    .getOrElse(throw IllegalStateException(s"There is no extension $name."))
    .loadModule()
    .asInstanceOf[BrowserExtension[?]]

  private def fields(bootstrap: Bootstrap): js.Object =
    val extension = js.Dynamic.literal(extension = bootstrap.extension)
    js.Object.assign(extension, roleFields(bootstrap.role))

  private def roleFields(role: Role): js.Object = role match
    case Role.Background           => js.Dynamic.literal(role = "background")
    case Role.ContentScript(index) =>
      js.Dynamic.literal(role = "content", index = index)
    case Role.Page(kind)      => js.Dynamic.literal(role = kind.id)
    case Role.Describe(build) => js
        .Dynamic
        .literal(
          role = "describe",
          target = build.target.id,
          version = build.version,
          geckoId = build.geckoId,
        )

  private def parse(fields: js.Dynamic): Option[Bootstrap] =
    role(fields).map(Bootstrap(
      fields.extension.asInstanceOf[String],
      _,
    ))

  private def role(fields: js.Dynamic): Option[Role] =
    fields.role.asInstanceOf[String] match
      case "background" => Some(Role.Background)
      case "content" => Some(Role.ContentScript(fields.index.asInstanceOf[Int]))
      case "popup"   => Some(Role.Page(PageKind.Popup))
      case "options" => Some(Role.Page(PageKind.Options))
      case "describe" => build(fields).map(Role.Describe(_))
      case _          => None

  private def build(fields: js.Dynamic): Option[Build] = Target
    .parse(fields.target.asInstanceOf[String])
    .map(Build(
      _,
      fields.version.asInstanceOf[String],
      fields.geckoId.asInstanceOf[String],
    ))
