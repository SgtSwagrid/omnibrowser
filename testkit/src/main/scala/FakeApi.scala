package com.alecdorrington.omnibrowser.testkit

import scala.scalajs.js
import scala.util.Try

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
    if context.kind.isContent then js.undefined
    else FakeTabs(simulation, context)

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

  /** Whether nothing listens. */
  def isQuiet: Boolean = listeners.isEmpty

  /**
    * Calls each listener with `arguments`, and gives what each returned, or
    * threw, as one listener's failure does not keep the others from hearing.
    */
  def hear(arguments: js.Any*): Seq[Try[js.Any]] = listeners
    .toSeq
    .map(listener => Try(listener.call(js.undefined, arguments*)))

  /**
    * Calls each listener with `arguments`, and gives what each returned, or
    * throws, once each has heard, what the first that failed threw.
    */
  def fire(arguments: js.Any*): Seq[js.Any] = hear(arguments*).map(_.get)

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

  /** Why the last call failed, while a listener hears of it. */
  private var error: js.UndefOr[js.Object] = js.undefined

  /** Whether the extension has read [[error]] since it was given. */
  private var read = true

  def lastError: js.UndefOr[js.Object] =
    read = true
    error

  /**
    * Does `action` with `failure` as the last error, and gives whether the
    * extension read it meanwhile, as Chromium reports it as an error otherwise.
    */
  private[testkit] final def failing
    (failure: js.Error, action: () => Unit)
    : Boolean =
    error = failure
    read = false
    try action()
    finally error = js.undefined
    read

  val onMessage: FakeEvent   = FakeEvent()
  val onConnect: FakeEvent   = FakeEvent()
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

private[testkit] final class FakeTabs
  (
    simulation: Simulation,
    context: FakeContext,
  )
  extends js.Object:

  def query(info: js.Dynamic): js.Promise[js.Array[js.Object]] = js
    .Promise
    .resolve(simulation.query(info))

  def create(properties: js.Dynamic): js.Promise[js.Object] = js
    .Promise
    .resolve(simulation.create(properties.url.asInstanceOf[String]))

  def sendMessage
    (
      tabId: Int,
      message: js.Any,
      options: js.UndefOr[js.Object],
    )
    : js.Promise[js.Any] = simulation.deliver(context, tabId, message)

  val onRemoved: FakeEvent = FakeEvent()

  def connect(tabId: Int, info: js.UndefOr[js.Dynamic]): FakePort = simulation
    .connect(
      context,
      tabId,
      info.toOption.fold("")(FakeTabs.name),
    )

private[testkit] object FakeTabs:

  /** The name of a port that `info` asks for, which is `""` if it gives none. */
  def name(info: js.Dynamic): String =
    if js.isUndefined(info.name) then "" else info.name.asInstanceOf[String]

/**
  * One end of a port between two contexts of a [[FakeBrowser]], which gives the
  * other end what is posted to it, after serialising it as JSON, once the
  * poster has carried on, as the browser does. It is mutable, as the ports that
  * it fakes are.
  */
private[testkit] final class FakePort
  (val name: String, simulation: Simulation)
  extends js.Object:

  val onMessage: FakeEvent    = FakeEvent()
  val onDisconnect: FakeEvent = FakeEvent()

  /** The other end, to which this end posts. */
  private[testkit] final var peer: js.UndefOr[FakePort] = js.undefined

  private var connected = true

  def postMessage(message: js.Any): Unit =
    if connected then
      val json = simulation.serialised(message)
      peer.foreach(other => simulation.later(other.receive(json)))
    else
      throw js.JavaScriptException(
        js.Error("Attempting to use a disconnected port object"),
      )

  /** Disconnects both ends, of which only the other end hears. */
  def disconnect(): Unit = if connected then
    connected = false
    peer.foreach(other => simulation.later(other.drop()))

  /** Gives this end's listeners `message`, unless it has disconnected. */
  private[testkit] final def receive(message: js.Any): Unit =
    if connected then onMessage.fire(message, this)

  /** Whether this end is still connected. */
  private[testkit] final def isOpen: Boolean = connected

  /** Disconnects this end, of which neither end hears. */
  private[testkit] final def close(): Unit = connected = false

  /** Disconnects this end, as the other end did, and tells its listeners. */
  private[testkit] final def drop(): Unit = if connected then
    connected = false
    onDisconnect.fire(this)

private[testkit] object FakePort:

  /** The two ends of a new port named `name`, each the other's peer. */
  def pair(name: String, simulation: Simulation): (FakePort, FakePort) =
    val (near, far) = (FakePort(name, simulation), FakePort(name, simulation))
    near.peer = far
    far.peer = near
    (near, far)

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
