package com.alecdorrington.omnibrowser.testkit

import scala.scalajs.js

/**
  * The WebExtensions API that one context of a [[FakeBrowser]] sees, which the
  * library's own implementation of [[com.alecdorrington.omnibrowser.Browser]]
  * uses as it would the browser's. Content scripts have no `tabs` or `action`.
  */
private[testkit] final class FakeApi
  (
    simulation: Simulation,
    context: FakeContext,
  )
  extends js.Object:

  val runtime: FakeRuntime = FakeRuntime(simulation, context)
  val storage: FakeStorage = FakeStorage(simulation, context)

  val tabs: js.UndefOr[FakeTabs] =
    if context.kind.isContent then js.undefined else FakeTabs(simulation)

  val action: js.UndefOr[FakeAction] =
    if context.kind.isContent then js.undefined else FakeAction(simulation)

  val permissions: js.UndefOr[FakePermissions] =
    if context.kind.isContent then js.undefined else FakePermissions(simulation)

/** An event, to which the extension adds listeners. */
private[testkit] final class FakeEvent extends js.Object:

  private val listeners = js.Array[js.Function]()

  def addListener(listener: js.Function): Unit = listeners.push(listener)

  def removeListener(listener: js.Function): Unit =
    listeners.filterInPlace(_ != listener)

  /** Calls each listener with `arguments`, and gives what each returned. */
  def fire(arguments: js.Any*): Seq[js.Any] = listeners
    .toSeq
    .map(_.call(js.undefined, arguments*))

private[testkit] final class FakeRuntime
  (
    simulation: Simulation,
    context: FakeContext,
  )
  extends js.Object:

  val id: String = Simulation.id

  def getURL(path: String): String = s"${ simulation.origin }/$path"

  def sendMessage(message: js.Any): js.Promise[js.Any] =
    simulation.deliver(context, message)

  val onMessage: FakeEvent   = FakeEvent()
  val onInstalled: FakeEvent = FakeEvent()
  val onStartup: FakeEvent   = FakeEvent()

private[testkit] final class FakeStorage
  (
    simulation: Simulation,
    context: FakeContext,
  )
  extends js.Object:

  val local: FakeStorageArea   = FakeStorageArea(simulation, context, "local")
  val sync: FakeStorageArea    = FakeStorageArea(simulation, context, "sync")
  val session: FakeStorageArea = FakeStorageArea(simulation, context, "session")
  val onChanged: FakeEvent     = FakeEvent()

private[testkit] final class FakeStorageArea
  (
    simulation: Simulation,
    context: FakeContext,
    area: String,
  )
  extends js.Object:

  def get(keys: js.Array[String]): js.Promise[js.Dictionary[js.Any]] =
    simulation.access(context, area)(simulation.read(area, keys))

  def set(items: js.Dictionary[js.Any]): js.Promise[Unit] =
    simulation.access(context, area)(simulation.write(area, items))

  def remove(keys: js.Array[String]): js.Promise[Unit] =
    simulation.access(context, area)(simulation.delete(area, keys))

private[testkit] final class FakeTabs(simulation: Simulation) extends js.Object:

  def query(info: js.Dynamic): js.Promise[js.Array[js.Object]] = js
    .Promise
    .resolve(simulation.query(info))

  def create(properties: js.Dynamic): js.Promise[js.Object] = js
    .Promise
    .resolve(simulation.create(properties.url.asInstanceOf[String]))

private[testkit] final class FakeAction
  (simulation: Simulation)
  extends js.Object:

  def setBadgeText(details: js.Dynamic): js.Promise[Unit] = js
    .Promise
    .resolve(simulation.setBadge(
      details.text.asInstanceOf[String],
      tabOf(details),
    ))

  def setBadgeBackgroundColor(details: js.Dynamic): js.Promise[Unit] = js
    .Promise
    .resolve(simulation.setBadgeColour(
      details.color.asInstanceOf[String],
      tabOf(details),
    ))

  val onClicked: FakeEvent = FakeEvent()

  /** The tab that `details` names, if any, or else every tab. */
  private def tabOf(details: js.Dynamic): Option[Int] = Option.unless(
    js.isUndefined(details.tabId),
  )(details.tabId.asInstanceOf[Int])

private[testkit] final class FakePermissions
  (simulation: Simulation)
  extends js.Object:

  def request(asked: js.Dynamic): js.Promise[Boolean] =
    simulation.request(asked)

  def contains(asked: js.Dynamic): js.Promise[Boolean] = js
    .Promise
    .resolve(simulation.holds(asked))

  def remove(asked: js.Dynamic): js.Promise[Boolean] = js
    .Promise
    .resolve(simulation.revoke(asked))

/**
  * A stand-in for a page's document, when the tests cannot load jsdom, which
  * holds only a body of text and a title. Making anything of it fails.
  */
private[testkit] final class FakeDocument
  (text: String, val title: String)
  extends js.Object:

  val body: FakeBody = FakeBody(text)

  def createElement(tag: String): js.Any = throw NoDom()

  def createElementNS(namespace: String, tag: String): js.Any = throw NoDom()

  def createTextNode(text: String): js.Any = throw NoDom()

  def createComment(text: String): js.Any = throw NoDom()

/** A page's body, whose `innerText` and `textContent` are the same. */
private[testkit] final class FakeBody(initial: String) extends js.Object:

  private var text = initial

  def innerText: String = text

  def innerText_=(value: String): Unit = text = value

  def textContent: String = text

  def textContent_=(value: String): Unit = text = value
