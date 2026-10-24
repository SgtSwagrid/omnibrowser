package com.alecdorrington.omnibrowser

import cats.effect.IO
import scala.scalajs.reflect.annotation.EnableReflectiveInstantiation

/**
  * A browser extension, defined by an object extending this class, which the
  * `sbt-omnibrowser` plugin builds for each target browser.
  *
  * @tparam P
  *   The extension's permissions, as an intersection of [[Permission]] types
  *   such as `Permission.Storage & Permission.Tabs`, or `Any` for none. They
  *   are requested in the extension's manifest, and the code within the
  *   extension may use only the APIs that they grant.
  */
@EnableReflectiveInstantiation
abstract class BrowserExtension[P : Permissions as P]:

  /** The name shown to the user. */
  def name: String

  /** What the extension does, in a sentence. */
  def description: String = ""

  /** Icons by their size in pixels, as paths among the extension's assets. */
  def icons: Map[Int, String] = Map.empty

  /** Handles events while no page of the extension need be open. */
  def background: Background = Background()

  /** The scripts injected into the pages they match. */
  def contentScripts: Seq[ContentScript] = Seq.empty

  /** The page shown when the user clicks the extension's toolbar button. */
  def popup: Option[Page] = None

  /** The page in which the user changes the extension's settings. */
  def options: Option[Page] = None

  /**
    * The data that the extension collects or sends elsewhere, which Firefox
    * shows the user on installing it.
    */
  def dataCollection: DataCollection = DataCollection.none

  /** The manifest names of the extension's permissions. */
  final def permissions: Set[String] = P.names

  /** Grants the code within the extension each of its permissions. */
  given granted[Q >: P]: Granted[Q] = Granted[Q]()

  /** Handles the extension's installation, or its update to a new version. */
  protected final def onInstalled
    (run: (InstallReason, Browser) => IO[Unit])
    : Handler = Handler.Installed(run)

  /** Handles the start of the browser profile that the extension is in. */
  protected final def onStartup(run: Browser => IO[Unit]): Handler = Handler
    .Startup(run)

  /**
    * Handles a click of the extension's toolbar button, which the browser
    * reports only if the extension has no [[popup]].
    */
  protected final def onActionClicked
    (run: (Tab, Browser) => IO[Unit])
    : Handler = Handler.ActionClicked(run)

  /** Answers the requests that the extension's other parts make of `endpoint`. */
  protected final def serve[Req, Res]
    (endpoint: Endpoint[Req, Res])
    (run: (Req, Browser) => IO[Res])
    : Handler = Handler.Serve(endpoint, run)
