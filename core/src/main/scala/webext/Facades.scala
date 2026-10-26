package com.alecdorrington.omnibrowser.webext

import scala.scalajs.js

/**
  * The WebExtensions API, as Firefox, Safari and the Chromium browsers share
  * it, in so far as the library uses it.
  */
@js.native
private[omnibrowser] trait WebExtensionApi extends js.Object:

  val runtime: RuntimeApi         = js.native
  val storage: StorageApi         = js.native
  val tabs: TabsApi               = js.native
  val action: ActionApi           = js.native
  val permissions: PermissionsApi = js.native

private[omnibrowser] object WebExtensionApi:

  /**
    * The API, named `browser` in Firefox and Safari, and `chrome` in Chromium
    * browsers.
    */
  lazy val current: WebExtensionApi =
    if js.typeOf(js.Dynamic.global.browser) == "undefined" then
      js.Dynamic.global.chrome.asInstanceOf[WebExtensionApi]
    else js.Dynamic.global.browser.asInstanceOf[WebExtensionApi]

/** An event, to which listeners of type `L` may be added. */
@js.native
private[omnibrowser] trait Event[L <: js.Function] extends js.Object:

  def addListener(listener: L): Unit    = js.native
  def removeListener(listener: L): Unit = js.native

/**
  * A listener for messages, which returns `true` if it will respond, through
  * the function it is given, once it has finished.
  */
private[omnibrowser] type MessageListener = js.Function3[
  js.Any,
  js.Any,
  js.Function1[js.Any, Unit],
  js.Any,
]

@js.native
private[omnibrowser] trait RuntimeApi extends js.Object:

  val id: String                                       = js.native
  def getURL(path: String): String                     = js.native
  def sendMessage(message: js.Any): js.Promise[js.Any] = js.native
  val onMessage: Event[MessageListener]                = js.native
  val onConnect: Event[js.Function1[PortApi, Unit]]    = js.native

  /**
    * Why the last call failed, as during the listener of a port that could not
    * connect, which Chrome reports as an error unless the listener reads it.
    */
  def lastError: js.UndefOr[js.Object]                         = js.native
  val onInstalled: Event[js.Function1[InstalledDetails, Unit]] = js.native
  val onStartup: Event[js.Function0[Unit]]                     = js.native

/**
  * One end of a long-lived channel between two parts of an extension, through
  * which each may send the other messages until either disconnects it.
  */
@js.native
private[omnibrowser] trait PortApi extends js.Object:

  val name: String                                    = js.native
  def postMessage(message: js.Any): Unit              = js.native
  def disconnect(): Unit                              = js.native
  val onMessage: Event[js.Function1[js.Any, Unit]]    = js.native
  val onDisconnect: Event[js.Function1[js.Any, Unit]] = js.native

@js.native
private[omnibrowser] trait InstalledDetails extends js.Object:
  val reason: String = js.native

@js.native
private[omnibrowser] trait StorageApi extends js.Object:

  val local: StorageAreaApi   = js.native
  val sync: StorageAreaApi    = js.native
  val session: StorageAreaApi = js.native

  val onChanged: Event[StorageListener] = js.native

/** A listener for changes to storage, given them by key, and their area. */
private[omnibrowser] type StorageListener = js.Function2[
  js.Dictionary[StorageChange],
  String,
  Unit,
]

@js.native
private[omnibrowser] trait StorageAreaApi extends js.Object:

  def get(keys: js.Array[String]): js.Promise[js.Dictionary[js.Any]] = js.native

  def set(items: js.Dictionary[js.Any]): js.Promise[Unit] = js.native
  def remove(keys: js.Array[String]): js.Promise[Unit]    = js.native

@js.native
private[omnibrowser] trait StorageChange extends js.Object:
  val newValue: js.UndefOr[js.Any] = js.native

@js.native
private[omnibrowser] trait TabsApi extends js.Object:

  def query(info: js.Object): js.Promise[js.Array[TabApi]] = js.native
  def create(properties: js.Object): js.Promise[TabApi]    = js.native

  def sendMessage
    (
      tabId: Int,
      message: js.Any,
      options: js.Object,
    )
    : js.Promise[js.Any] = js.native

  def connect(tabId: Int, info: js.Object): PortApi = js.native

@js.native
private[omnibrowser] trait TabApi extends js.Object:

  val id: js.UndefOr[Int]       = js.native
  val windowId: Int             = js.native
  val active: Boolean           = js.native
  val url: js.UndefOr[String]   = js.native
  val title: js.UndefOr[String] = js.native

@js.native
private[omnibrowser] trait PermissionsApi extends js.Object:

  def request(permissions: js.Object): js.Promise[Boolean]  = js.native
  def contains(permissions: js.Object): js.Promise[Boolean] = js.native
  def remove(permissions: js.Object): js.Promise[Boolean]   = js.native

@js.native
private[omnibrowser] trait ActionApi extends js.Object:

  def setBadgeText(details: js.Object): js.Promise[Unit]            = js.native
  def setBadgeBackgroundColor(details: js.Object): js.Promise[Unit] = js.native
  val onClicked: Event[js.Function1[TabApi, Unit]]                  = js.native
