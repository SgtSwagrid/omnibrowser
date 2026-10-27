package com.alecdorrington.omnibrowser.testkit

import cats.effect.IO
import cats.syntax.all.*
import com.alecdorrington.omnibrowser.*
import com.alecdorrington.omnibrowser.launch.PageKind
import com.alecdorrington.omnibrowser.webext.{
  TabApi, WebExtensionApi, WebExtensionBrowser, WebExtensionStorage,
  WebExtensionTabs,
}
import com.alecdorrington.omnibrowser.webext.Circe.*
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
  ).map((tab, document) => FakePage(document, Some(TabId(tab.id)), settle))

  /** Closes `tab`, as the user would. */
  def close(tab: TabId): IO[Unit] = act(simulation.close(tab.toInt))

  /**
    * Has `tab` load `url` showing `text`, as on following a link, in place of
    * its page, into which the browser injects each of the extension's content
    * scripts that match it.
    */
  def navigate(tab: TabId, url: String, text: String = ""): IO[FakePage] = act(
    simulation.navigate(tab.toInt, url, text),
  ).map(FakePage(_, Some(tab), settle))

  /** How many ports are open, for the testkit's own tests. */
  private[testkit] def openPorts: IO[Int] = IO(simulation.openPorts)

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

  /** Sends `request` as `message`, as a content script would. */
  def call[Req, Res](message: Message[Req, Res])(request: Req): IO[Res] = tester
    .runtime
    .call(message)(request) <* settle

  /**
    * Sends `request` as `message` to the content scripts of `tab` that serve
    * it, as the background would.
    */
  def call[Req, Res]
    (
      message: Message.Tab[Req, Res],
      tab: TabId,
    )
    (request: Req)
    : IO[Res] = tester.tabs.call(message, tab)(request) <* settle

  /** The value of `shared` in `tab`, as the background would see it. */
  def shared[A](shared: Shared.Tab[A], tab: TabId): IO[A] = tester
    .tabs
    .get(shared, tab) <* settle

  /**
    * The stored value of `key`, or its default if what is stored cannot be
    * decoded, as the extension would see it.
    */
  def stored[A](key: StorageKey[A]): IO[A] = IO(
    simulation.stored(key.area.id, key.name),
  ).flatMap(WebExtensionStorage.read(key, _))

  /** Stores `value` under `key`, as though another device had synced it. */
  def store[A](key: StorageKey[A], value: A): IO[Unit] = act(simulation.write(
    key.area.id,
    js.Dictionary(key.name -> key.codec.encodeJs(value)),
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

  private def openPage(kind: PageKind): IO[FakePage] =
    act(simulation.openPage(kind))
      .flatMap(IO.fromOption(_)(NoSuchPage(kind.id)))
      .map(FakePage(_, None, settle))

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

/**
  * A page open in a [[FakeBrowser]]: a tab's, or one of the extension's own.
  *
  * Its document is jsdom's, if the tests can load it, as from their
  * `node_modules`, and the test may then act on its elements as the user would.
  * Each such action waits until the extension has finished responding to it,
  * and fails if the extension failed meanwhile. Otherwise, it is a stand-in
  * with only a body of text.
  */
final class FakePage private[testkit] (
  document: js.Dynamic,
  val tab: Option[TabId],
  settle: IO[Unit],
):

  /** The text of the page's body. */
  def text: IO[String] = IO(document.body.textContent.asInstanceOf[String])

  /** The text of the first element that the CSS `selector` matches. */
  def text(selector: String): IO[String] =
    element(selector).map(_.textContent.asInstanceOf[String])

  /** The value of the first field that `selector` matches. */
  def value(selector: String): IO[String] =
    element(selector).map(_.value.asInstanceOf[String])

  /**
    * Whether the first checkbox or radio button that `selector` matches is
    * checked.
    */
  def isChecked(selector: String): IO[Boolean] =
    element(selector).map(_.checked.asInstanceOf[Boolean])

  /** Clicks the first element that `selector` matches. */
  def click(selector: String): IO[Unit] = act(selector)(_.click())

  /**
    * Types `text` into the first field that `selector` matches, in place of its
    * value, and then leaves the field.
    */
  def enter(selector: String, text: String): IO[Unit] = act(selector): field =>
    field.value = text
    fire(field, "input")
    fire(field, "change")

  private def act(selector: String)(action: js.Dynamic => Unit): IO[Unit] =
    element(selector).flatMap(element => IO(action(element))) >> settle

  /** The first element that `selector` matches. */
  private def element(selector: String): IO[js.Dynamic] = (document: Any) match
    case _: FakeDocument => IO.raiseError(NoDom())
    case _ => IO(document.querySelector(selector)).flatMap: found =>
        IO.raiseWhen(found == null)(NoSuchElement(selector)).as(found)

  /** Fires the event `name` at `element`, as the browser would. */
  private def fire(element: js.Dynamic, name: String): Unit =
    val event = document.createEvent("Event")
    event.initEvent(name, true, true)
    element.dispatchEvent(event)

/** The extension has no page of the kind asked for. */
final case class NoSuchPage(kind: String)
  extends Exception(s"The extension has no $kind page.")

/** No element of the page matches the CSS selector `selector`. */
final case class NoSuchElement(selector: String)
  extends Exception(s"No element of the page matches $selector.")

/** The page has no DOM, as the tests could not load jsdom. */
final case class NoDom()
  extends Exception(
    "The page has no DOM, as the tests could not load jsdom. Install it with `npm install --save-dev jsdom`.",
  )

/**
  * The extension did not read `runtime.lastError` while a listener heard of the
  * failure `reason`, which Chromium reports as an error.
  */
final case class UncheckedError(reason: String)
  extends Exception(s"Unchecked runtime.lastError: $reason")

/** The extension was still busy when the test gave up waiting for it. */
final case class Unsettled()
  extends Exception("The extension was still busy after five seconds.")

/** The tests were not given the directory of the built extension. */
final case class NoBuild()
  extends Exception(
    s"${ FakeBrowser
        .buildVariable } is not set. Run the tests through sbt, with the sbt-omnibrowser plugin enabled.",
  )
