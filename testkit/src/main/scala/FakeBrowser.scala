package com.alecdorrington.omnibrowser.testkit

import cats.effect.IO
import cats.syntax.all.*
import com.alecdorrington.omnibrowser.*
import com.alecdorrington.omnibrowser.launch.PageKind
import com.alecdorrington.omnibrowser.webext.{
  TabApi, WebExtensionApi, WebExtensionBrowser, WebExtensionTabs,
}
import scala.concurrent.duration.*
import scala.scalajs.js

/**
  * A browser that keeps everything in memory, into which a test installs an
  * extension, then acts as the user would and checks what the extension did.
  *
  * Each action waits until the extension has finished responding to it, and
  * fails if the extension failed meanwhile.
  */
final class FakeBrowser private (
  simulation: Simulation,
  activity: Activity,
):

  /** The test's own context, from which it makes requests. */
  private lazy val tester = WebExtensionBrowser(
    simulation.context(Kind.Test).api.asInstanceOf[WebExtensionApi],
    activity.launch,
  )

  /**
    * Opens a tab at `url` showing `text`, into which the browser injects each
    * of the extension's content scripts that match it.
    */
  def visit(url: String, text: String = ""): IO[FakePage] = act(
    simulation.openTab(url, text),
  ).map((tab, document) => FakePage(document, Some(TabId(tab.id))))

  /** Opens the extension's popup. */
  def openPopup: IO[FakePage] = openPage(PageKind.Popup)

  /** Opens the extension's options page. */
  def openOptions: IO[FakePage] = openPage(PageKind.Options)

  /**
    * Clicks the extension's toolbar button, which the extension hears of only
    * if it has no popup.
    */
  def clickAction: IO[Unit] = act(simulation.clickAction())

  /** Restarts the browser profile, as the extension hears of it. */
  def restart: IO[Unit] = act(simulation.startup())

  /** Makes a request of `endpoint`, as a content script would. */
  def call[Req, Res](endpoint: Endpoint[Req, Res])(request: Req): IO[Res] =
    tester.runtime.call(endpoint)(request) <* settle

  /** The stored value of `key`. */
  def stored[A](key: StorageKey[A]): IO[A] = IO(
    simulation.stored(key.area.id, key.name),
  ).flatMap(json => IO.fromEither(decode(key, json)))

  /** Stores `value` under `key`, as though another device had synced it. */
  def store[A](key: StorageKey[A], value: A): IO[Unit] = act(simulation.write(
    key.area.id,
    js.Dictionary(key.name -> key.codec.encode(value)),
  ))

  /** The text on the extension's badge, in tabs without their own. */
  def badge: IO[String] = IO(simulation.badge)

  /** The colour of the extension's badge, if it has been given one. */
  def badgeColour: IO[Option[String]] = IO(simulation.badgeColour)

  /** The text on the extension's badge in `tab`. */
  def badgeOf(tab: TabId): IO[String] = IO(simulation.badgeOf(tab.toInt))

  /** The colour of the extension's badge in `tab`, if it has one. */
  def badgeColourOf(tab: TabId): IO[Option[String]] =
    IO(simulation.badgeColourOf(tab.toInt))

  /**
    * Answers each later request for permissions with `grant`, as the user
    * would. The fake user grants them until told otherwise.
    */
  def answerRequests(grant: Boolean): IO[Unit] =
    IO(simulation.grantsRequests = grant)

  /** The open tabs, as the extension sees them. */
  def tabs: IO[List[Tab]] = IO(
    simulation
      .query(js.Dynamic.literal())
      .toList
      .map(tab => WebExtensionTabs.tab(tab.asInstanceOf[TabApi])),
  )

  /**
    * Waits until the extension has been idle for a moment, with no message
    * awaiting its response, and fails if the extension failed meanwhile.
    */
  def settle: IO[Unit] = poll(-1, 0, FakeBrowser.patience)

  /** This browser, once the extension is installed into it. */
  private def installed: IO[FakeBrowser] = act(simulation.install()).as(this)

  private def openPage(kind: PageKind): IO[FakePage] = act(
    simulation.openPage(kind),
  ).flatMap(IO.fromOption(_)(NoSuchPage(kind.id))).map(FakePage(_, None))

  private def act[A](action: => A): IO[A] = IO(action) <* settle

  private def poll(events: Int, quiet: Int, remaining: Int): IO[Unit] = IO
    .sleep(FakeBrowser.interval) >>
    IO(activity.status).flatMap(next(_, events, quiet, remaining))

  private def next
    (
      status: Status,
      events: Int,
      quiet: Int,
      remaining: Int,
    )
    : IO[Unit] = status match
    case Status(_, _, failure +: _) => IO(activity.forgetFailures()) >>
        IO.raiseError(failure)
    case Status(true, `events`, _) if quiet >= FakeBrowser.calm => IO.unit
    case _ if remaining == 0  => IO.raiseError(Unsettled())
    case Status(idle, now, _) => poll(
        now,
        if idle && now == events then quiet + 1 else 0,
        remaining - 1,
      )

  private def decode[A]
    (key: StorageKey[A], json: Option[js.Any])
    : Either[StorageError, A] = json
    .fold(key.default.asRight[String])(key.codec.decode)
    .leftMap(StorageError(key.name, _))

object FakeBrowser:

  /**
    * The environment variable naming the directory into which `sbt-omnibrowser`
    * builds the extension for development, which it sets for the tests.
    */
  val buildVariable = "OMNIBROWSER_BUILD"

  /** How often to see whether the extension is idle. */
  private val interval = 5.millis

  /** How many times in a row the extension must be idle to have settled. */
  private val calm = 3

  /** How many times to look before giving up, which is after five seconds. */
  private val patience = 1000

  /**
    * A fake browser into which `extension` is installed, with its background
    * started and its installation reported, running within the test itself.
    */
  def install(extension: BrowserExtension[?]): IO[FakeBrowser] =
    start(activity => InProcess(extension, activity.launch))

  /**
    * A fake browser into which the extension that `sbt-omnibrowser` built for
    * `target` is installed, with each part in a sandbox of its own.
    */
  def installBuilt(target: Target): IO[FakeBrowser] = IO
    .fromOption(Node.env(buildVariable))(NoBuild())
    .flatMap(directory => installBuilt(s"$directory/${ target.id }", target))

  /**
    * A fake browser into which the extension built for `target` in `directory`
    * is installed, with each part in a sandbox of its own.
    */
  def installBuilt(directory: String, target: Target): IO[FakeBrowser] =
    start(activity => Built(directory, target, activity))

  private def start(runner: Activity => Runner): IO[FakeBrowser] = IO.defer:
    val activity = Activity()
    val browser  = FakeBrowser(
      Simulation(runner(activity), activity),
      activity,
    )
    browser.installed

/** A page open in a [[FakeBrowser]]: a tab's, or one of the extension's own. */
final class FakePage private[testkit] (
  document: FakeDocument,
  val tab: Option[TabId],
):

  /** The text of the page's body. */
  def text: IO[String] = IO(document.body.textContent)

/** The extension has no page of the kind asked for. */
final case class NoSuchPage(kind: String)
  extends Exception(s"The extension has no $kind page.")

/** The extension was still busy when the test gave up waiting for it. */
final case class Unsettled()
  extends Exception("The extension was still busy after five seconds.")

/** The tests were not given the directory of the built extension. */
final case class NoBuild()
  extends Exception(
    s"${ FakeBrowser
        .buildVariable } is not set. Run the tests through sbt, with the sbt-omnibrowser plugin enabled.",
  )
