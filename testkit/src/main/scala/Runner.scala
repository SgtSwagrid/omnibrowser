package com.alecdorrington.omnibrowser.testkit

import cats.effect.IO
import com.alecdorrington.omnibrowser.{BrowserExtension, Target}
import com.alecdorrington.omnibrowser.launch.{Launcher, PageKind}
import com.alecdorrington.omnibrowser.webext.{
  WebExtensionApi, WebExtensionBrowser,
}
import scala.scalajs.js
import scala.util.Try

/** How a [[FakeBrowser]] starts each part of an extension. */
private[testkit] trait Runner:

  /** The browser that the extension is built for. */
  def target: Target

  /** The extension's name. */
  def name: String

  /** The manifest names of the extension's permissions. */
  def permissions: Set[String]

  /** The pages that the extension may access, as match patterns. */
  def hosts: Seq[String]

  /** The manifest names of the permissions that the extension may ask for. */
  def optionalPermissions: Set[String]

  /** The pages that the extension may ask to access, as match patterns. */
  def optionalHosts: Seq[String]

  /** The match patterns of each content script, in order. */
  def contentScripts: Seq[Seq[String]]

  /** Whether the extension has a page of `kind`. */
  def hasPage(kind: PageKind): Boolean

  /** Starts the extension's background in `context`. */
  def startBackground(context: FakeContext): Unit

  /**
    * Opens a tab at `url` whose body holds `text`, into which the content
    * scripts at `indices` are injected, in `context`, and gives its document.
    */
  def openTab
    (
      url: String,
      text: String,
      indices: Seq[Int],
      context: => FakeContext,
    )
    : js.Dynamic

  /**
    * Opens the extension's page of `kind` at `url`, if it has one, in
    * `context`, and gives its document.
    */
  def openPage
    (
      kind: PageKind,
      url: String,
      context: FakeContext,
    )
    : Option[js.Dynamic]

/**
  * Runs an extension's code within the test itself, starting each program that
  * it runs with `launch`.
  *
  * Every part shares the test's global scope, so its pages' documents take
  * turns as the global `document`, unless the tests have a DOM of their own.
  */
private[testkit] final class InProcess
  (
    extension: BrowserExtension[?],
    launch: IO[Unit] => Unit,
  )
  extends Runner:

  private val launcher = Launcher(launch)

  override def target: Target = Target.Chromium

  override def name: String = extension.name

  override def permissions: Set[String] = extension.permissions

  override def hosts: Seq[String] = extension.hosts

  override def optionalPermissions: Set[String] =
    extension.optionalPermissions.names

  override def optionalHosts: Seq[String] = extension.optionalHosts

  override def contentScripts: Seq[Seq[String]] = extension
    .contentScripts()
    .map(_.matches)

  override def hasPage(kind: PageKind): Boolean = kind.of(extension).isDefined

  override def startBackground(context: FakeContext): Unit = launcher
    .background(
      extension,
      browser(context),
      api(context),
    )

  override def openTab
    (
      url: String,
      text: String,
      indices: Seq[Int],
      context: => FakeContext,
    )
    : js.Dynamic =
    val document = Dom.tab(url, text)
    val scripts  = indices.flatMap(extension.contentScripts().lift)
    if scripts.nonEmpty then
      show(document)
      val tab = browser(context)
      scripts.foreach(launcher.contentScript(_, tab))
    document

  override def openPage
    (
      kind: PageKind,
      url: String,
      context: FakeContext,
    )
    : Option[js.Dynamic] = kind
    .of(extension)
    .map: page =>
      val document = Dom.page(extension.name, page.body)
      show(document)
      launcher.page(page, browser(context))
      document

  private def api(context: FakeContext): WebExtensionApi = context
    .api
    .asInstanceOf[WebExtensionApi]

  private def browser(context: FakeContext): WebExtensionBrowser =
    WebExtensionBrowser(api(context), launch)

  /** Makes `document` the global document, unless the tests have a DOM. */
  private def show(document: js.Dynamic): Unit = if !InProcess.hasOwnDom then
    js.Dynamic.global.globalThis.document = document

private[testkit] object InProcess:

  /**
    * Whether the tests have a DOM of their own, as they would in a browser,
    * whose document is not to be replaced. Only the tests could have given the
    * global `document` before it is first replaced.
    */
  private lazy val hasOwnDom: Boolean =
    !js.isUndefined(js.Dynamic.global.globalThis.document)

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

  override def hosts: Seq[String] = strings(field(manifest, "host_permissions"))

  override def optionalPermissions: Set[String] =
    strings(field(manifest, "optional_permissions")).toSet

  override def optionalHosts: Seq[String] =
    strings(field(manifest, "optional_host_permissions"))

  override def contentScripts: Seq[Seq[String]] =
    scripts.map(script => strings(field(script, "matches")))

  override def hasPage(kind: PageKind): Boolean = page(kind).isDefined

  override def startBackground(context: FakeContext): Unit =
    run(backgroundScripts, Frame.empty, context)

  override def openTab
    (
      url: String,
      text: String,
      indices: Seq[Int],
      context: => FakeContext,
    )
    : js.Dynamic =
    val frame = Dom.tabFrame(url, text)
    val files = indices
      .flatMap(scripts.lift)
      .flatMap(script => strings(field(script, "js")))
    run(files, frame.scope, context)
    frame.document

  override def openPage
    (
      kind: PageKind,
      url: String,
      context: FakeContext,
    )
    : Option[js.Dynamic] = page(kind).map: page =>
    val html  = read(page)
    val frame = Dom.pageFrame(url, name, html)
    run(
      Built.scriptsOf(html),
      frame.scope,
      context,
    )
    frame.document

  /** Runs the scripts `files`, if there are any, in `scope`, in `context`. */
  private def run
    (
      files: Seq[String],
      scope: js.Dynamic,
      context: => FakeContext,
    )
    : Unit = if files.nonEmpty then
    val sandbox = Sandbox(
      directory,
      context,
      scope,
      target,
      activity,
    )
    files.foreach(sandbox.load)

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
  * A sandbox, whose global scope is `scope`, in which one part of a built
  * extension runs, seeing the API of `context` and, if it is a page, its
  * document.
  */
private[testkit] final class Sandbox
  (
    directory: String,
    context: FakeContext,
    scope: js.Dynamic,
    target: Target,
    activity: Activity,
  ):

  private val globals = Sandbox.shared.filterNot((name, _) => has(name)) ++ Seq(
    "console"               -> SandboxConsole(activity),
    Sandbox.apiName(target) -> context.api,
    // Chromium's and Safari's background is a service worker, which loads the
    // bundle itself.
    "importScripts" ->
      (((file: String) => load(file)): js.Function1[String, Unit]),
  )

  globals.foreach((name, value) => scope.updateDynamic(name)(value))

  /** Whether the scope already has the global `name`, as a window has most. */
  private def has(name: String): Boolean =
    !js.isUndefined(scope.selectDynamic(name))

  /** Runs the script `file` of the extension in the sandbox. */
  def load(file: String): Unit = Node
    .vm
    .runInContext(
      Node.fs.readFileSync(s"$directory/$file", "utf8"),
      scope,
      js.Dynamic.literal(filename = file),
    )

private[testkit] object Sandbox:

  /**
    * The globals of Node.js that every part of an extension may use, which a
    * sandbox is given where its scope lacks them.
    */
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
    case Target.Chromium                => "chrome"
    case Target.Firefox | Target.Safari => "browser"

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

  /**
    * The package `name`, if the tests can load it, as from the `node_modules`
    * of the directory in which they run, or of one above it.
    */
  def load(name: String): Option[js.Dynamic] = Try(
    builtin[js.Dynamic]("node:module").createRequire(
      s"${ js.Dynamic.global.process.cwd() }/",
    )(name),
  ).toOption

  private def builtin[A](name: String): A = js
    .Dynamic
    .global
    .process
    .getBuiltinModule(name)
    .asInstanceOf[A]

@js.native
private[testkit] trait Vm extends js.Object:

  def createContext(globals: js.Object): js.Dynamic = js.native

  def runInContext
    (
      code: String,
      context: js.Any,
      options: js.Object,
    )
    : js.Any = js.native

@js.native
private[testkit] trait Fs extends js.Object:
  def readFileSync(path: String, encoding: String): String = js.native
