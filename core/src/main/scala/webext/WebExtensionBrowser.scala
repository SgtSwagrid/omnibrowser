package com.alecdorrington.omnibrowser.webext

import cats.Eq
import cats.effect.{IO, Resource}
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

  override lazy val tabs: Browser.Tabs = WebExtensionTabs(api.tabs, api.runtime)

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

/**
  * The browser's tabs, through `api`, and through `runtime` the announcements
  * of the pages in them that begin sharing values.
  */
private[omnibrowser] final class WebExtensionTabs
  (api: TabsApi, runtime: RuntimeApi)
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

  override def get[A](shared: Shared.Tab[A], tab: TabId): IO[A] = ask(
    tab,
    Messages.request(shared.reading, shared.name),
  ).flatMap(_.traverse(Messages.response(shared.reading, _)))
    .adaptError { case MessagingError(_, reason) =>
      MessagingError(shared.name, reason)
    }
    .map(_.getOrElse(shared.default))

  override def values[A](shared: Shared.Tab[A], tab: TabId): Stream[IO, A] =
    (Interop
      .eventsFrom(())(runtime.onMessage)(announced(tab))
      .switchMap(_ => watch(shared, tab))
      .interruptWhen(closing(tab)) ++ Stream.emit(shared.default)).changes(
      using Eq.fromUniversalEquals,
    )

  private def query(info: js.Object): IO[List[Tab]] = promise(api.query(info))
    .map(_.toList.map(WebExtensionTabs.tab))

  /**
    * The value of `shared` in the page now in `tab`, then each new value, until
    * the page goes, and then its default.
    */
  private def watch[A](shared: Shared.Tab[A], tab: TabId): Stream[IO, A] =
    Interop
      .port(
        api.connect(tab.toInt, connection(shared.name)),
        runtime,
      )
      .evalMap(Messages.watched(shared, _))
      .map(_.getOrElse(shared.default)) ++ Stream.emit(shared.default)

  /**
    * A listener that gives `emit` nothing each time that the page in `tab`
    * announces that it has begun sharing values, and leaves every message to
    * the other listeners.
    */
  private def announced(tab: TabId)(emit: Unit => Unit): MessageListener =
    (message, sender, _) =>
      if isAnnouncement(message) &&
        Messages.sender(sender).tab.exists(_.id == tab)
      then emit(())
      false

  /** Whether each tab that closes is `tab`, which ends a watch of it. */
  private def closing(tab: TabId): Stream[IO, Boolean] = Interop.events(
    api.onRemoved,
  )(emit => (closed: Int, _: js.Any) => emit(closed == tab.toInt))

  private def isAnnouncement(message: js.Any): Boolean = Messages
    .endpoint(message)
    .contains(Shared.announcement.name)

  /**
    * The answer to `message` from the content scripts of `tab`, if any answered
    * it. Where it reaches nothing, as where there are none, none answered it.
    */
  private def ask(tab: TabId, message: js.Any): IO[Option[js.Any]] =
    promise(send(tab, message)).redeem(
      _ => None,
      Option(_).filter(Messages.isAnswer),
    )

  /** Sends `message` to the content scripts of `tab`'s page. */
  private def send(tab: TabId, message: js.Any): js.Promise[js.Any] = api
    .sendMessage(
      tab.toInt,
      message,
      WebExtensionTabs.page,
    )

  /** How to connect a port that watches `name` to a page's content scripts. */
  private def connection(name: String): js.Object = js
    .Object
    .assign(
      js.Dynamic.literal(name = Shared.port(name)),
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
