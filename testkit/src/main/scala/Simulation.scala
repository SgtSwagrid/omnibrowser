package com.alecdorrington.omnibrowser.testkit

import com.alecdorrington.omnibrowser.Target
import com.alecdorrington.omnibrowser.launch.{Json, Layout, PageKind}
import scala.collection.mutable
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

/** Which part of the extension, or of a page, a context belongs to. */
private[testkit] enum Kind:

  case Background

  case Page

  /** The world of the extension's content scripts in the page of `tab`. */
  case Content(tab: Int)

  /** The test itself, which makes requests as a content script would. */
  case Test

  /** Whether the context runs a content script. */
  def isContent: Boolean = this match
    case Content(_) => true
    case _          => false

  /** Whether `runtime.sendMessage` reaches the context. */
  def isExtension: Boolean = this == Background || this == Page

/** A context of the fake browser, with an API of its own. */
private[testkit] final class FakeContext
  (val kind: Kind, simulation: Simulation):
  val api: FakeApi = FakeApi(simulation, this)

/** A tab of the fake browser. */
private[testkit] final case class FakeTab(id: Int, url: String)

/**
  * The fake browser itself, whose state every context's API shares, and which
  * starts each part of the extension through `runner`. It is mutable, as the
  * API that it fakes is.
  */
private[testkit] final class Simulation(runner: Runner, activity: Activity):

  private val areas = Seq("local", "sync", "session")
    .map(_ -> js.Dictionary[js.Any]())
    .toMap

  private val contexts = mutable.ArrayBuffer.empty[FakeContext]
  private var tabs     = Vector.empty[FakeTab]
  private var active   = Option.empty[Int]

  /** The text on the extension's badge, in tabs without their own. */
  var badge = ""

  /** The colour of the extension's badge, if it has been given one. */
  var badgeColour = Option.empty[String]

  private val tabBadges  = mutable.Map.empty[Int, String]
  private val tabColours = mutable.Map.empty[Int, String]

  /** Whether the fake user grants the permissions that the extension asks for. */
  var grantsRequests = true

  /** The optional permissions and hosts that the fake user has granted. */
  private val granted = mutable.Set.empty[String]

  /** Where the extension's files are, as URLs. */
  def origin: String = runner.target match
    case Target.Chrome  => s"chrome-extension://${ Simulation.id }"
    case Target.Firefox => s"moz-extension://${ Simulation.id }"

  /** A new context of `kind`. */
  def context(kind: Kind): FakeContext =
    val context = FakeContext(kind, this)
    contexts += context
    context

  /** Installs the extension: starts its background, then reports it. */
  def install(): Unit =
    val background = context(Kind.Background)
    runner.startBackground(background)
    background
      .api
      .runtime
      .onInstalled
      .fire(js.Dynamic.literal(reason = "install"))

  /** Reports the start of the browser profile. */
  def startup(): Unit = extension.foreach(_.api.runtime.onStartup.fire())

  /**
    * Clicks the extension's toolbar button, which the background hears of only
    * if the extension has no popup.
    */
  def clickAction(): Unit = if !runner.hasPage(PageKind.Popup) then
    val tab = tabObject(activeTab.getOrElse(openTab("about:blank", "")._1))
    background.foreach(_.api.action.foreach(_.onClicked.fire(tab)))

  /**
    * Opens a tab at `url` showing `text`, into which the content scripts that
    * match it are injected, and gives the tab and its document.
    */
  def openTab(url: String, text: String): (FakeTab, js.Dynamic) =
    val tab = FakeTab(tabs.size + 1, url)
    tabs :+= tab
    active = Some(tab.id)
    val document = runner.openTab(
      url,
      text,
      scriptsFor(url),
      context(Kind.Content(tab.id)),
    )
    (tab, document)

  /**
    * Opens the extension's page of `kind`, if it has one, and gives its
    * document.
    */
  def openPage(kind: PageKind): Option[js.Dynamic] = Option
    .when(runner.hasPage(kind))(context(Kind.Page))
    .flatMap(runner.openPage(
      kind,
      s"$origin/${ Layout.page(kind) }",
      _,
    ))

  /**
    * Delivers `message` from `sender` to each of the extension's own contexts,
    * after serialising it as JSON, as Chrome does.
    */
  def deliver(sender: FakeContext, message: js.Any): js.Promise[js.Any] =
    activity.touch()
    new js.Promise[js.Any]((resolve, reject) =>
      val listeners = receivers(sender)
      if listeners.isEmpty then
        reject(js.Error(
          "Could not establish connection. Receiving end does not exist.",
        ))
      else
        respond(
          listeners,
          serialised(message),
          senderOf(sender),
          resolve(_),
        ),
    )

  /** Whether `context` may use the storage `area`, and if so, `result`. */
  def access[A]
    (context: FakeContext, area: String)
    (result: => A)
    : js.Promise[A] =
    activity.touch()
    if context.kind.isContent && area == "session" then
      js.Promise
        .reject(js.Error("Access to storage is not allowed from this context."))
    else js.Promise.resolve(result)

  /** The stored values of `keys` in `area`. */
  def read(area: String, keys: js.Array[String]): js.Dictionary[js.Any] = js
    .Dictionary(keys.toSeq.flatMap(key => stored(area, key).map(key -> _))*)

  /** The stored value of `key` in `area`, if there is one. */
  def stored(area: String, key: String): Option[js.Any] = areas(area)
    .get(key)
    .map(serialised)

  /** Stores `items` in `area`, and reports the changes to every context. */
  def write
    (
      area: String,
      items: js.Dictionary[js.Any],
    )
    : Unit =
    items.foreach((key, value) => change(area, key, Some(serialised(value))))

  /** Removes `keys` from `area`, and reports the changes to every context. */
  def delete(area: String, keys: js.Array[String]): Unit =
    keys.foreach(change(area, _, None))

  /** The tabs that `info` asks for, of which only `active` is understood. */
  def query(info: js.Dynamic): js.Array[js.Object] = tabs
    .filter(tab =>
      js.isUndefined(info.active) ||
      info.active.asInstanceOf[Boolean] == isActive(tab),
    )
    .map(tabObject)
    .toJSArray

  /** Opens a tab at `url`, as the extension asked. */
  def create(url: String): js.Object = tabObject(openTab(url, "")._1)

  /** Shows `text` on the badge in `tab`, or else in every tab. */
  def setBadge(text: String, tab: Option[Int]): Unit =
    activity.touch()
    tab match
      case Some(id) => tabBadges(id) = text
      case None     => badge = text

  /** Colours the badge in `tab`, or else in every tab. */
  def setBadgeColour(colour: String, tab: Option[Int]): Unit =
    activity.touch()
    tab match
      case Some(id) => tabColours(id) = colour
      case None     => badgeColour = Some(colour)

  /** The text on the badge in `tab`. */
  def badgeOf(tab: Int): String = tabBadges.getOrElse(tab, badge)

  /** The colour of the badge in `tab`, if it has been given one. */
  def badgeColourOf(tab: Int): Option[String] = tabColours
    .get(tab)
    .orElse(badgeColour)

  /**
    * Asks the fake user for the permissions and hosts that `asked` names, as
    * `permissions.request` does, which may ask only for those declared.
    */
  def request(asked: js.Dynamic): js.Promise[Boolean] =
    activity.touch()
    val wanted     = names(asked)
    val undeclared = wanted.filterNot(isDeclared)
    if undeclared.nonEmpty then
      js.Promise
        .reject(js.Error(
          "Only permissions specified in the manifest may be requested: " +
            undeclared.toSeq.sorted.mkString(", "),
        ))
    else
      if grantsRequests then granted ++= wanted
      js.Promise.resolve(grantsRequests)

  /** Whether the extension holds every permission and host that `asked` names. */
  def holds(asked: js.Dynamic): Boolean = names(asked).forall(isHeld)

  /** Gives up the optional permissions and hosts that `asked` names. */
  def revoke(asked: js.Dynamic): Boolean =
    activity.touch()
    val wanted = names(asked)
    granted --= wanted
    !wanted.exists(isRequired)

  /**
    * A tab as the API describes it, with its URL only if the extension may see
    * it.
    */
  def tabObject(tab: FakeTab): js.Object =
    val fields = js
      .Dynamic
      .literal(
        id = tab.id,
        windowId = 1,
        active = isActive(tab),
      )
    if isHeld("tabs") then
      js.Object
        .assign(
          fields,
          js.Dynamic.literal(url = tab.url, title = tab.url),
        )
    else fields

  private def background: Option[FakeContext] =
    contexts.find(_.kind == Kind.Background)

  private def extension: Seq[FakeContext] = contexts
    .filter(_.kind.isExtension)
    .toSeq

  private def activeTab: Option[FakeTab] = tabs.find(isActive)

  private def isActive(tab: FakeTab): Boolean = active.contains(tab.id)

  /** The indices of the content scripts that match `url`. */
  private def scriptsFor(url: String): Seq[Int] = runner
    .contentScripts
    .zipWithIndex
    .collect:
      case (patterns, index) if patterns.exists(MatchPattern.matches(_, url)) =>
        index

  private def receivers(sender: FakeContext): Seq[FakeContext] = extension
    .filter(_ != sender)

  /**
    * Gives each of `contexts` `message`, and `resolve` the first response, or
    * nothing if none of them will respond.
    */
  private def respond
    (
      contexts: Seq[FakeContext],
      message: js.Any,
      sender: js.Object,
      resolve: js.Any => Unit,
    )
    : Unit =
    activity.await()
    var answered                           = false
    val answer: js.Function1[js.Any, Unit] = response =>
      if !answered then
        answered = true
        activity.answer()
        resolve(serialised(response))
    val replies =
      contexts.flatMap(_.api.runtime.onMessage.fire(message, sender, answer))
    if !replies.exists(isTrue) then answer(js.undefined)

  private def change
    (
      area: String,
      key: String,
      value: Option[js.Any],
    )
    : Unit =
    activity.touch()
    val old = areas(area).get(key)
    value match
      case Some(json) => areas(area)(key) = json
      case None       => areas(area).remove(key)
    val change = js.Dictionary[js.Any](
      (old.map("oldValue" -> _) ++ value.map("newValue" -> _)).toSeq*,
    )
    val changes = js.Dictionary[js.Any](key -> change)
    contexts.foreach(_.api.storage.onChanged.fire(changes, area))

  private def names(asked: js.Dynamic): Set[String] =
    strings(asked.permissions) ++ strings(asked.origins)

  private def strings(json: js.Dynamic): Set[String] =
    if js.isUndefined(json) then Set.empty
    else json.asInstanceOf[js.Array[String]].toSet

  private def isRequired(name: String): Boolean = runner.permissions(name) ||
    runner.hosts.contains(name)

  private def isDeclared(name: String): Boolean = isRequired(name) ||
    runner.optionalPermissions(name) || runner.optionalHosts.contains(name)

  private def isHeld(name: String): Boolean = isRequired(name) || granted(name)

  /** Who sent a message from `context`, as the browser describes them. */
  private def senderOf(context: FakeContext): js.Object = context.kind match
    case Kind.Content(id) => tabs
        .find(_.id == id)
        .fold(js.Dynamic.literal(id = Simulation.id))(tab =>
          js.Dynamic
            .literal(
              id = Simulation.id,
              tab = tabObject(tab),
              url = tab.url,
            ),
        )
    case _ => js.Dynamic.literal(id = Simulation.id)

  private def isTrue(reply: js.Any): Boolean = js.typeOf(reply) == "boolean" &&
    reply.asInstanceOf[Boolean]

  /** `value` after a round trip through JSON, as the browser serialises it. */
  private def serialised(value: js.Any): js.Any =
    if js.isUndefined(value) then value
    else js.JSON.parse(Json.stringify(value))

private[testkit] object Simulation:

  /** The extension's ID in the fake browser. */
  val id = "omnibrowser-test"
