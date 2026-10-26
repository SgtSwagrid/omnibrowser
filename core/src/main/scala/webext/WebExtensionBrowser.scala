package com.alecdorrington.omnibrowser.webext

import cats.effect.{Deferred, IO, Resource}
import cats.syntax.all.*
import com.alecdorrington.omnibrowser.*
import com.alecdorrington.omnibrowser.webext.Interop.{events, promise}
import fs2.Stream
import fs2.concurrent.Signal
import scala.scalajs.js

/**
  * The browser, through the WebExtensions API, which starts the programs that
  * the extension runs with `launch`. It is what every part sees, though only a
  * content script sees it as a [[TabBrowser]], and only the background and
  * pages as a [[Browser]].
  */
private[omnibrowser] final class WebExtensionBrowser
  (
    api: WebExtensionApi,
    launch: IO[Unit] => Unit,
  )
  extends Browser, TabBrowser:

  override def run(program: IO[Unit]): Unit = launch(program)

  override def serve[Req, Res]
    (endpoint: Endpoint.Tab[Req, Res])
    (run: Req => IO[Res])
    : Resource[IO, Unit] = sharing.serve(endpoint.endpoint)(run)

  override def share[A]
    (shared: Shared.Tab[A])
    (signal: Signal[IO, A])
    : Resource[IO, Unit] = sharing.share(shared, signal)

  private lazy val sharing = WebExtensionSharing(api.runtime, launch)

  override lazy val grants: Browser.Grants = WebExtensionGrants(api.permissions)

  override lazy val runtime: Browser.Runtime = WebExtensionRuntime(api.runtime)

  override def storage(using Granted[Permission.Storage]): Browser.Storage =
    WebExtensionStorage(api.storage)

  override lazy val tabs: Browser.Tabs = WebExtensionTabs(api.tabs)

  override lazy val action: Browser.Action = WebExtensionAction(api.action)

private[omnibrowser] final class WebExtensionRuntime
  (api: RuntimeApi)
  extends Browser.Runtime:

  override def id: String = api.id

  override def url(path: String): String = api.getURL(path)

  override def call[Req, Res]
    (endpoint: Endpoint[Req, Res])
    (request: Req)
    : IO[Res] = Messages.exchange(endpoint, request)(api.sendMessage)

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

  /**
    * Gives anyone who asks the value of `signal` as that of `shared`, and each
    * port that connects for it the value, then each new value, while in use.
    */
  def share[A]
    (
      shared: Shared.Tab[A],
      signal: Signal[IO, A],
    )
    : Resource[IO, Unit] =
    for
      released <- Resource.make(Deferred[IO, Unit])(_.complete(()).void)
      _        <- serve(shared.endpoint)(_ => signal.get)
      _        <- Interop.listening(
        api.onConnect,
        connected(shared, signal, released),
      )
    yield ()

  /**
    * A listener that publishes `signal` through each port that connects for
    * `shared`, until the port disconnects, or until `released`.
    */
  private def connected[A]
    (
      shared: Shared.Tab[A],
      signal: Signal[IO, A],
      released: Deferred[IO, Unit],
    )
    : js.Function1[PortApi, Unit] = port =>
    if port.name == shared.name then
      val gone = Deferred.unsafe[IO, Unit]
      port.onDisconnect.addListener(_ => launch(gone.complete(()).void))
      launch(
        publish(port, shared.codec, signal)
          .race(gone.get)
          .race(released.get)
          .guarantee(IO(port.disconnect()))
          .void,
      )

  /** Sends through `port` the value of `signal`, then each new value. */
  private def publish[A]
    (
      port: PortApi,
      codec: Codec[A],
      signal: Signal[IO, A],
    )
    : IO[Unit] = signal
    .discrete
    .foreach(value => post(port, codec.encode(value)))
    .compile
    .drain

  /**
    * Sends the value `json` through `port`, unless it has disconnected, as it
    * may have before this side has heard.
    */
  private def post(port: PortApi, json: js.Any): IO[Unit] =
    IO(port.postMessage(Messages.value(json))).handleError(_ => ())

private[omnibrowser] final class WebExtensionStorage
  (api: StorageApi)
  extends Browser.Storage:

  override def get[A](key: StorageKey[A]): IO[A] = promise(
    area(key).get(js.Array(key.name)),
  ).flatMap(items => read(key, items.get(key.name)))

  override def set[A](key: StorageKey[A], value: A): IO[Unit] =
    promise(area(key).set(js.Dictionary(key.name -> key.codec.encode(value))))

  override def remove[A](key: StorageKey[A]): IO[Unit] =
    promise(area(key).remove(js.Array(key.name)))

  override def changes[A](key: StorageKey[A]): Stream[IO, A] =
    events[Option[js.Any], StorageListener](api.onChanged)(changed(key, _))
      .evalMap(read(key, _))

  /**
    * A listener for changes to storage, which gives `emit` each new value of
    * `key`, or nothing when it is removed.
    */
  private def changed
    (
      key: StorageKey[?],
      emit: Option[js.Any] => Unit,
    )
    : StorageListener = (changes, area) =>
    if area == key.area.id then
      changes.get(key.name).foreach(change => emit(change.newValue.toOption))

  private def area(key: StorageKey[?]): StorageAreaApi = key.area match
    case StorageArea.Local   => api.local
    case StorageArea.Sync    => api.sync
    case StorageArea.Session => api.session

  private def read[A](key: StorageKey[A], json: Option[js.Any]): IO[A] = IO
    .fromEither(
      json
        .fold(key.default.asRight[String])(key.codec.decode)
        .leftMap(StorageError(key.name, _)),
    )

private[omnibrowser] final class WebExtensionTabs
  (api: TabsApi)
  extends Browser.Tabs:

  override def all: IO[List[Tab]] = query(js.Dynamic.literal())

  override def active: IO[Option[Tab]] = query(
    js.Dynamic.literal(active = true, currentWindow = true),
  ).map(_.headOption)

  override def open(url: String): IO[Tab] =
    promise(api.create(js.Dynamic.literal(url = url))).map(WebExtensionTabs.tab)

  override def call[Req, Res]
    (
      endpoint: Endpoint.Tab[Req, Res],
      tab: TabId,
    )
    (request: Req)
    : IO[Res] = Messages.exchange(endpoint.endpoint, request)(send(tab, _))

  override def get[A](shared: Shared.Tab[A], tab: TabId): IO[A] =
    find(shared, tab).map(_.getOrElse(shared.default))

  override def values[A](shared: Shared.Tab[A], tab: TabId): Stream[IO, A] =
    Interop
      .port(api.connect(tab.toInt, connection(shared.name)))
      .evalMap(Messages.value(shared, _))
      .interruptWhen(unshared(shared, tab)) ++ Stream.emit(shared.default)

  private def query(info: js.Object): IO[List[Tab]] = promise(api.query(info))
    .map(_.toList.map(WebExtensionTabs.tab))

  /** The value of `shared` in `tab`, if a content script there shares it. */
  private def find[A](shared: Shared.Tab[A], tab: TabId): IO[Option[A]] = ask(
    tab,
    Messages.request(shared.endpoint, ()),
  ).flatMap(_.traverse(Messages.response(shared.endpoint, _)))

  /**
    * The answer to `message` from the content scripts of `tab`, if any answered
    * it. Where it reaches nothing, as where there are none, none answered it.
    */
  private def ask(tab: TabId, message: js.Any): IO[Option[js.Any]] =
    promise(send(tab, message)).redeem(
      _ => None,
      Option(_).filter(Messages.isAnswer),
    )

  /**
    * Completes once it finds that nothing in `tab` shares `shared`, and
    * otherwise never.
    */
  private def unshared[A]
    (shared: Shared.Tab[A], tab: TabId)
    : IO[Either[Throwable, Unit]] = find(shared, tab)
    .flatMap(found => IO.never.whenA(found.isDefined))
    .attempt

  /** Sends `message` to the content scripts of `tab`'s page. */
  private def send(tab: TabId, message: js.Any): js.Promise[js.Any] = api
    .sendMessage(
      tab.toInt,
      message,
      WebExtensionTabs.page,
    )

  /** How to connect a port named `name` to the content scripts of a page. */
  private def connection(name: String): js.Object = js
    .Object
    .assign(
      js.Dynamic.literal(name = name),
      WebExtensionTabs.page,
    )

private[omnibrowser] object WebExtensionTabs:

  /**
    * The options of a message or port to a tab that reach only the content
    * scripts of its page, rather than those of the frames within it.
    */
  def page: js.Object = js.Dynamic.literal(frameId = 0)

  /** The tab that the API describes as `tab`. */
  def tab(tab: TabApi): Tab = Tab(
    id = TabId(tab.id.getOrElse(-1)),
    windowId = tab.windowId,
    active = tab.active,
    url = tab.url.toOption,
    title = tab.title.toOption,
  )

private[omnibrowser] final class WebExtensionAction
  (api: ActionApi)
  extends Browser.Action:

  override def setBadge(text: String): IO[Unit] =
    promise(api.setBadgeText(js.Dynamic.literal(text = text)))

  override def setBadge(text: String, tab: TabId): IO[Unit] = promise(
    api.setBadgeText(js.Dynamic.literal(text = text, tabId = tab.toInt)),
  )

  override def setBadgeColour(colour: String): IO[Unit] =
    promise(api.setBadgeBackgroundColor(js.Dynamic.literal(color = colour)))

  override def setBadgeColour(colour: String, tab: TabId): IO[Unit] =
    promise(api.setBadgeBackgroundColor(
      js.Dynamic.literal(color = colour, tabId = tab.toInt),
    ))

private[omnibrowser] final class WebExtensionGrants
  (api: PermissionsApi)
  extends Browser.Grants:

  override def request[Q : Permissions as Q]: IO[Option[Granted[Q]]] =
    promise(api.request(named(Q))).map(evidence[Q])

  override def granted[Q : Permissions as Q]: IO[Option[Granted[Q]]] =
    promise(api.contains(named(Q))).map(evidence[Q])

  override def revoke[Q : Permissions as Q]: IO[Unit] =
    promise(api.remove(named(Q))).void

  override def requestHosts(patterns: String*): IO[Boolean] =
    promise(api.request(hosts(patterns)))

  override def hasHosts(patterns: String*): IO[Boolean] =
    promise(api.contains(hosts(patterns)))

  private def named(permissions: Permissions[?]): js.Object = js
    .Dynamic
    .literal(permissions = js.Array(permissions.names.toSeq*))

  private def hosts(patterns: Seq[String]): js.Object = js
    .Dynamic
    .literal(origins = js.Array(patterns*))

  private def evidence[Q](granted: Boolean): Option[Granted[Q]] =
    Option.when(granted)(Granted[Q]())
