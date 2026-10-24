package com.alecdorrington.omnibrowser.webext

import cats.effect.IO
import cats.syntax.all.*
import com.alecdorrington.omnibrowser.*
import com.alecdorrington.omnibrowser.webext.Interop.{events, promise}
import fs2.Stream
import scala.scalajs.js

/** The browser, through the WebExtensions API. */
private[omnibrowser] final class WebExtensionBrowser
  (api: WebExtensionApi)
  extends Browser:

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
    : IO[Res] = promise(api.sendMessage(Messages.request(endpoint, request)))
    .adaptError { case js.JavaScriptException(error) =>
      MessagingError(endpoint.name, error.toString)
    }
    .flatMap(Messages.response(endpoint, _))

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

  private def query(info: js.Object): IO[List[Tab]] = promise(api.query(info))
    .map(_.toList.map(WebExtensionTabs.tab))

private[omnibrowser] object WebExtensionTabs:

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

  override def setBadgeColour(colour: String): IO[Unit] =
    promise(api.setBadgeBackgroundColor(js.Dynamic.literal(color = colour)))
