package com.alecdorrington.omnibrowser.webext

import cats.effect.{Deferred, IO, Resource}
import cats.syntax.all.*
import com.alecdorrington.omnibrowser.{Codec, Endpoint, Shared}
import com.alecdorrington.omnibrowser.webext.Interop.promise
import fs2.concurrent.Signal
import scala.collection.mutable
import scala.scalajs.js
import scala.util.Try

/**
  * What a content script offers the rest of the extension from its tab, through
  * `api`, starting the programs that it runs with `launch`.
  */
private[omnibrowser] final class WebExtensionSharing
  (api: RuntimeApi, launch: IO[Unit] => Unit):

  /** Answers the requests of `endpoint` through `run`, while in use. */
  def serve[Req, Res]
    (endpoint: Endpoint[Req, Res])
    (run: Req => IO[Res])
    : Resource[IO, Unit] = Interop.listening(
    api.onMessage,
    Messages.listener(endpoint, launch)((request, _) => run(request)),
  )

  /** Makes `signal` the owner of `shared` in this script's page, while in use. */
  def share[A]
    (
      shared: Shared.Tab[A],
      signal: Signal[IO, A],
    )
    : Resource[IO, Unit] = Resource
    .make(IO(owning(Hub.of(api, launch), shared, signal)))(unshare =>
      IO(unshare()),
    )
    .void

  /**
    * Makes `signal` the owner of `shared` in `hub`, and gives the function that
    * stops it.
    */
  private def owning[A]
    (
      hub: HubApi,
      shared: Shared.Tab[A],
      signal: Signal[IO, A],
    )
    : js.Function0[Unit] = hub.share(
    shared.name,
    Owner(shared.codec, signal, hub, launch).asInstanceOf[OwnerApi],
  )

/** The owner of a shared value in a page, as the page's [[Hub]] sees it. */
@js.native
private[omnibrowser] trait OwnerApi extends js.Object:

  /**
    * Sends through `port` the value, then each new value, until the function
    * that it gives is called.
    */
  def attach(port: PortApi): js.Function0[Unit] = js.native

  /** Gives `respond` the response to a request for the value. */
  def read(respond: js.Function1[js.Any, Unit]): Unit = js.native

/** The values that the content scripts of a page share, as each sees them. */
@js.native
private[omnibrowser] trait HubApi extends js.Object:

  /**
    * Makes `owner` the owner of the value named `name`, and gives the function
    * that stops it.
    */
  def share(name: String, owner: OwnerApi): js.Function0[Unit] = js.native

  /** Forgets `port`, which is gone, and stops sending it anything. */
  def drop(port: PortApi): Unit = js.native

/**
  * The owner of a shared value in a page, which publishes `signal` through each
  * port that `hub` gives it, and drops any that it finds gone, as Firefox may
  * not say when a port goes. Each of its programs starts with `launch`. Only
  * the page's hub calls it, perhaps from another copy of the content bundle, so
  * its methods take and give only JavaScript's values.
  */
private final class Owner[A]
  (
    codec: Codec[A],
    signal: Signal[IO, A],
    hub: HubApi,
    launch: IO[Unit] => Unit,
  )
  extends js.Object:

  def attach(port: PortApi): js.Function0[Unit] =
    // Stopped at once, so that nothing is sent after what the hub sends next.
    var live    = true
    val stopped = Deferred.unsafe[IO, Unit]
    launch(publish(port, () => live).race(stopped.get).void)
    () =>
      live = false
      launch(stopped.complete(()).void)

  def read(respond: js.Function1[js.Any, Unit]): Unit = launch(
    signal
      .get
      .map(value => Messages.success(codec.encode(value)))
      .handleError(error => Messages.failure(Messages.reason(error)))
      .flatMap(response => IO(respond(response))),
  )

  /**
    * Sends through `port` the value, then each new value, while it is `live`,
    * until sending fails, when the port is gone.
    */
  private def publish(port: PortApi, live: () => Boolean): IO[Unit] = signal
    .discrete
    .foreach(value =>
      IO(if live() then port.postMessage(Messages.value(codec.encode(value)))),
    )
    .compile
    .drain
    .handleError(_ => hub.drop(port))

/**
  * The values that the content scripts of one page share, through `api`, which
  * every copy of the content bundle in the page uses, as each content script
  * loads one. A value's owner is the one that began sharing it last, of those
  * that still share it.
  *
  * It answers each port that connects for a value, as the background or a page
  * opens one to watch it, with the owner's value, then each new value, or with
  * a message that nothing shares it, whenever nothing does. It answers requests
  * for a value with the owner's value, and leaves them unanswered if there is
  * none. It is mutable, as owners and ports come and go.
  */
private final class Hub(api: RuntimeApi) extends js.Object:

  /** The owners of each value, the latest first. */
  private val owners = mutable.Map.empty[String, List[OwnerApi]]

  /** The ports that watch each value, with how to stop what each is sent. */
  private val watchers = mutable
    .Map
    .empty[
      String,
      mutable.Map[PortApi, js.Function0[Unit]],
    ]

  def share(name: String, owner: OwnerApi): js.Function0[Unit] =
    owners(name) = owner :: ownersOf(name)
    reattach(name)
    () => unshare(name, owner)

  def drop(port: PortApi): Unit =
    watchers.values.foreach(_.remove(port).foreach(stop => stop()))
    port.disconnect()

  /** Answers each port that connects for a value, and each request for one. */
  private[webext] final def listen(): Unit =
    api.onConnect.addListener(connected)
    api.onMessage.addListener(asked)

  private def unshare(name: String, owner: OwnerApi): Unit =
    val current = ownersOf(name).headOption.contains(owner)
    owners(name) = ownersOf(name).filterNot(_ == owner)
    if current then reattach(name)

  /** Gives each port that watches `name` what its owner now sends. */
  private def reattach(name: String): Unit = watchersOf(name)
    .toList
    .foreach((port, stop) =>
      stop()
      watchersOf(name)(port) = attach(name, port),
    )

  /**
    * Has the owner of `name` send `port` its values, or tells `port` that
    * nothing shares it, and gives how to stop what `port` is sent.
    */
  private def attach(name: String, port: PortApi): js.Function0[Unit] =
    ownersOf(name).headOption match
      case Some(owner) => owner.attach(port)
      case None        =>
        Hub.post(port, Messages.unshared)
        () => ()

  /** Watches the value for which `port` connects, if it watches one. */
  private def connected: js.Function1[PortApi, Unit] = port =>
    Shared
      .watched(port.name)
      .foreach: name =>
        port.onDisconnect.addListener(_ => drop(port))
        watchersOf(name)(port) = attach(name, port)

  /** Answers a request for a value with its owner's value, if it has one. */
  private def asked: MessageListener = (message, _, respond) =>
    Messages
      .endpoint(message)
      .filter(_ == Shared.reading)
      .flatMap(_ => Codec[String].decode(Messages.payload(message)).toOption)
      .flatMap(ownersOf(_).headOption)
      .fold(false): owner =>
        owner.read(respond)
        true

  private def ownersOf(name: String): List[OwnerApi] =
    owners.getOrElse(name, Nil)

  private def watchersOf
    (name: String)
    : mutable.Map[PortApi, js.Function0[Unit]] =
    watchers.getOrElseUpdate(name, mutable.Map.empty)

private object Hub:

  /**
    * The global under which pages keep their hubs, by the API of the world in
    * which the content scripts run, which every copy of the content bundle in a
    * page shares, and each page has its own of, as when tests run several pages
    * in one global scope.
    */
  private val key = "__omnibrowserShared"

  /**
    * The hub of the page whose API is `api`, made, and announced to the rest of
    * the extension, through programs that `launch` starts, if it has none yet.
    */
  def of(api: RuntimeApi, launch: IO[Unit] => Unit): HubApi =
    if !hubs.has(api).asInstanceOf[Boolean] then
      hubs.set(api, started(api, launch))
    hubs.get(api).asInstanceOf[HubApi]

  /** Sends `message` through `port`, unless it is gone. */
  def post(port: PortApi, message: js.Any): Unit =
    Try(port.postMessage(message)).getOrElse(())

  /**
    * A new hub of the page whose API is `api`, which listens, and which tells
    * the background and pages that the page has begun sharing values, so that
    * their watchers connect to it again, and tells them again each time that
    * the page returns.
    */
  private def started(api: RuntimeApi, launch: IO[Unit] => Unit): Hub =
    val hub = Hub(api)
    hub.listen()
    val announce = () =>
      launch(
        promise(api.sendMessage(Messages.request(Shared.announcement, ())))
          .void
          .handleError(_ => ()),
      )
    announce()
    onReturn(announce)
    hub

  /**
    * Runs `announce` each time that the page returns: from the back-forward
    * cache, which closed its ports, or into view after it was prerendered,
    * while its tab showed another page.
    */
  private def onReturn(announce: () => Unit): Unit =
    val global = js.Dynamic.global.globalThis
    listen(global, "pageshow")(event =>
      if event.persisted.asInstanceOf[js.UndefOr[Boolean]].contains(true) then
        announce(),
    )
    listen(global.document, "prerenderingchange")(_ => announce())

  /** Has `target`, if it is one, run `handle` with each event named `name`. */
  private def listen
    (target: js.Dynamic, name: String)
    (handle: js.Dynamic => Unit)
    : Unit =
    if !js.isUndefined(target) &&
      js.typeOf(target.addEventListener) == "function"
    then
      target.addEventListener(
        name,
        handle: js.Function1[js.Dynamic, Unit],
      )

  private def hubs: js.Dynamic =
    val global = js.Dynamic.global.globalThis
    if js.isUndefined(global.selectDynamic(key)) then
      global.updateDynamic(key)(
        js.Dynamic.newInstance(js.Dynamic.global.WeakMap)(),
      )
    global.selectDynamic(key)
