package com.alecdorrington.omnibrowser.testkit

import cats.effect.IO
import com.alecdorrington.omnibrowser.{BrowserExtension, Target}
import com.alecdorrington.omnibrowser.launch.{Launcher, PageKind}
import com.alecdorrington.omnibrowser.webext.{
  WebExtensionApi, WebExtensionBrowser,
}
import scala.scalajs.js

/** How a [[FakeBrowser]] starts each part of an extension. */
private[testkit] trait Runner:

  /** The browser that the extension is built for. */
  def target: Target

  /** The extension's name. */
  def name: String

  /** The manifest names of the extension's permissions. */
  def permissions: Set[String]

  /** The match patterns of each content script, in order. */
  def contentScripts: Seq[Seq[String]]

  /** Whether the extension has a page of `kind`. */
  def hasPage(kind: PageKind): Boolean

  /** Starts the extension's background in `context`. */
  def startBackground(context: FakeContext): Unit

  /** Starts the content scripts at `indices` in `context`, on `document`. */
  def startContentScripts
    (
      indices: Seq[Int],
      context: FakeContext,
      document: FakeDocument,
    )
    : Unit

  /** Starts the extension's page of `kind` in `context`, on `document`. */
  def startPage
    (
      kind: PageKind,
      context: FakeContext,
      document: FakeDocument,
    )
    : Unit

/**
  * Runs an extension's code within the test itself, starting each program that
  * it runs with `launch`.
  *
  * Every part shares the test's global scope, so its pages' documents take
  * turns as the global `document`, which is a stand-in with only a body of text
  * unless the tests have a DOM of their own.
  */
private[testkit] final class InProcess
  (
    extension: BrowserExtension[?],
    launch: IO[Unit] => Unit,
  )
  extends Runner:

  private val launcher = Launcher(launch)

  override def target: Target = Target.Chrome

  override def name: String = extension.name

  override def permissions: Set[String] = extension.permissions

  override def contentScripts: Seq[Seq[String]] = extension
    .contentScripts
    .map(_.matches)

  override def hasPage(kind: PageKind): Boolean = kind.of(extension).isDefined

  override def startBackground(context: FakeContext): Unit = launcher
    .background(
      extension,
      browser(context),
      api(context),
    )

  override def startContentScripts
    (
      indices: Seq[Int],
      context: FakeContext,
      document: FakeDocument,
    )
    : Unit =
    show(document)
    indices
      .flatMap(extension.contentScripts.lift)
      .foreach(launcher.contentScript(_, browser(context)))

  override def startPage
    (
      kind: PageKind,
      context: FakeContext,
      document: FakeDocument,
    )
    : Unit =
    show(document)
    kind.of(extension).foreach(launcher.page(_, browser(context)))

  private def api(context: FakeContext): WebExtensionApi = context
    .api
    .asInstanceOf[WebExtensionApi]

  private def browser(context: FakeContext): WebExtensionBrowser =
    WebExtensionBrowser(api(context))

  /** Makes `document` the global document, unless the tests have a DOM. */
  private def show(document: FakeDocument): Unit =
    val global       = js.Dynamic.global.globalThis
    val current: Any = global.document
    if js.isUndefined(current) || current.isInstanceOf[FakeDocument] then
      global.document = document

/**
  * Runs an extension as `sbt-omnibrowser` built it for `target`, in
  * `directory`, with each part in a sandbox of its own, as the browser would.
  * Anything that a part logs as an error is a failure.
  */
private[testkit] final class Built
  (
    directory: String,
    override val target: Target,
    activity: Activity,
  )
  extends Runner:

  private val manifest = js.JSON.parse(read(Built.manifest))

  override def name: String = manifest.name.asInstanceOf[String]

  override def permissions: Set[String] =
    strings(field(manifest, "permissions")).toSet

  override def contentScripts: Seq[Seq[String]] =
    scripts.map(script => strings(field(script, "matches")))

  override def hasPage(kind: PageKind): Boolean = page(kind).isDefined

  override def startBackground(context: FakeContext): Unit =
    val files = backgroundScripts
    if files.nonEmpty then
      val sandbox = Sandbox(
        directory,
        context,
        None,
        target,
        activity,
      )
      files.foreach(sandbox.load)

  override def startContentScripts
    (
      indices: Seq[Int],
      context: FakeContext,
      document: FakeDocument,
    )
    : Unit =
    val sandbox = Sandbox(
      directory,
      context,
      Some(document),
      target,
      activity,
    )
    indices
      .flatMap(scripts.lift)
      .flatMap(script => strings(field(script, "js")))
      .foreach(sandbox.load)

  override def startPage
    (
      kind: PageKind,
      context: FakeContext,
      document: FakeDocument,
    )
    : Unit = page(kind).foreach: page =>
    val sandbox = Sandbox(
      directory,
      context,
      Some(document),
      target,
      activity,
    )
    Built.scriptsOf(read(page)).foreach(sandbox.load)

  private def scripts: Seq[js.Dynamic] = field(manifest, "content_scripts")
    .toSeq
    .flatMap(_.asInstanceOf[js.Array[js.Dynamic]])

  private def backgroundScripts: Seq[String] = field(manifest, "background")
    .toSeq
    .flatMap: background =>
      field(background, "service_worker").map(_.asInstanceOf[String]).toSeq ++
        strings(field(background, "scripts"))

  private def page(kind: PageKind): Option[String] = (kind match
    case PageKind.Popup =>
      field(manifest, "action").flatMap(field(_, "default_popup"))
    case PageKind.Options =>
      field(manifest, "options_ui").flatMap(field(_, "page"))
  ).map(_.asInstanceOf[String])

  private def read(file: String): String = Node
    .fs
    .readFileSync(s"$directory/$file", "utf8")

  private def field(json: js.Dynamic, name: String): Option[js.Dynamic] =
    val value = json.selectDynamic(name)
    Option.unless(js.isUndefined(value))(value)

  private def strings(json: Option[js.Dynamic]): Seq[String] = json
    .toSeq
    .flatMap(_.asInstanceOf[js.Array[String]])

private[testkit] object Built:

  private val manifest = "manifest.json"

  /** The scripts that a page's HTML loads, in order. */
  def scriptsOf(html: String): Seq[String] = """<script src="([^"]+)""""
    .r
    .findAllMatchIn(html)
    .map(_.group(1))
    .toSeq

/**
  * A sandbox, with a global scope of its own, in which one part of a built
  * extension runs, seeing the API of `context` and, if it is a page, the
  * `document`.
  */
private[testkit] final class Sandbox
  (
    directory: String,
    context: FakeContext,
    document: Option[FakeDocument],
    target: Target,
    activity: Activity,
  ):

  private val globals = Sandbox.shared ++ Seq(
    "console"               -> SandboxConsole(activity),
    Sandbox.apiName(target) -> context.api,
  ) ++ document.map("document" -> _)

  private val sandbox = Node
    .vm
    .createContext(js.Dictionary(globals*).asInstanceOf[js.Object])

  // Chrome's background is a service worker, which loads the bundle itself.
  sandbox.asInstanceOf[js.Dynamic].importScripts =
    ((file: String) => load(file)): js.Function1[String, Unit]

  /** Runs the script `file` of the extension in the sandbox. */
  def load(file: String): Unit = Node
    .vm
    .runInContext(
      Node.fs.readFileSync(s"$directory/$file", "utf8"),
      sandbox,
      js.Dynamic.literal(filename = file),
    )

private[testkit] object Sandbox:

  /** The globals of Node.js that every part of an extension may use. */
  private def shared: Seq[(String, js.Any)] = Seq(
    "setTimeout",
    "clearTimeout",
    "setInterval",
    "clearInterval",
    "queueMicrotask",
    "MessageChannel",
    "performance",
    "structuredClone",
    "TextEncoder",
    "TextDecoder",
    "URL",
  ).map(name => name -> js.Dynamic.global.globalThis.selectDynamic(name))

  /** The global under which `target` offers the WebExtensions API. */
  private def apiName(target: Target): String = target match
    case Target.Chrome  => "chrome"
    case Target.Firefox => "browser"

/** The console of a [[Sandbox]], which records each error as a failure. */
private[testkit] final class SandboxConsole
  (activity: Activity)
  extends js.Object:

  def log(messages: js.Any*): Unit   = console.log(messages*)
  def info(messages: js.Any*): Unit  = console.info(messages*)
  def debug(messages: js.Any*): Unit = console.debug(messages*)
  def warn(messages: js.Any*): Unit  = console.warn(messages*)

  def error(messages: js.Any*): Unit =
    activity.fail(js.JavaScriptException(js.Array(messages*).join(" ")))
    console.error(messages*)

  private def console: js.Dynamic = js.Dynamic.global.console

/** The parts of Node.js that the testkit uses. */
private[testkit] object Node:

  lazy val vm: Vm = builtin("node:vm")

  lazy val fs: Fs = builtin("node:fs")

  /** The environment variable `name`, if it is set. */
  def env(name: String): Option[String] =
    val value = js.Dynamic.global.process.env.selectDynamic(name)
    Option.unless(js.isUndefined(value))(value.asInstanceOf[String])

  private def builtin[A](name: String): A = js
    .Dynamic
    .global
    .process
    .getBuiltinModule(name)
    .asInstanceOf[A]

@js.native
private[testkit] trait Vm extends js.Object:

  def createContext(globals: js.Object): js.Object = js.native

  def runInContext
    (
      code: String,
      context: js.Object,
      options: js.Object,
    )
    : js.Any = js.native

@js.native
private[testkit] trait Fs extends js.Object:
  def readFileSync(path: String, encoding: String): String = js.native
