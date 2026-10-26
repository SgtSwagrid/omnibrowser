package com.alecdorrington.omnibrowser

import cats.effect.{IO, Resource}
import fs2.Stream
import fs2.concurrent.Signal

/**
  * The browser, as every part of the extension sees it, its content scripts
  * included: with only some of its APIs.
  */
trait ContentBrowser:

  /** The extension itself, and the messages between its parts. */
  def runtime: Browser.Runtime

  /** The values that the extension keeps. */
  def storage(using Granted[Permission.Storage]): Browser.Storage

  /**
    * Starts `program` at once without waiting, as from the handler of a DOM
    * event. It runs without pausing until its first asynchronous step, so that
    * an API that needs the user's input, as [[Browser.Grants.request]] does,
    * still sees it. Any failure is reported to the console.
    */
  def run(program: IO[Unit]): Unit

/**
  * The browser, as a content script sees it: with only some of its APIs, and
  * with what the script offers the rest of the extension from its tab.
  */
trait TabBrowser extends ContentBrowser:

  /**
    * Answers the requests that the background and pages make of `endpoint` in
    * this script's tab, while the resource is in use.
    */
  def serve[Req, Res](endpoint: Endpoint.Tab[Req, Res])(run: Req => IO[Res])
    : Resource[IO, Unit]

  /**
    * Shares `signal` as the value of `shared` in this script's tab, while the
    * resource is in use, so that the background and pages see its value, then
    * each new value. If several content scripts of the page share it, the one
    * that began last is its owner, until it stops. Once none shares it, the
    * value there is the default again.
    *
    * A page that begins sharing values tells the background and pages, and
    * tells them again when it returns from the back-forward cache, or is shown
    * after being prerendered, so that those watching its tab find it. Each time
    * reaches every open page of the extension, and wakes the background if it
    * serves any endpoint.
    */
  def share[A](shared: Shared.Tab[A])(signal: Signal[IO, A]): Resource[IO, Unit]

/** The browser, as the extension's background and pages see it. */
trait Browser extends ContentBrowser:

  /** The browser's tabs. */
  def tabs: Browser.Tabs

  /** The extension's toolbar button. */
  def action: Browser.Action

  /** The optional permissions that the user grants while the extension runs. */
  def grants: Browser.Grants

object Browser:

  /** The extension itself, and the messages between its parts. */
  trait Runtime:

    /** The extension's ID. */
    def id: String

    /** The URL of the file at `path` within the extension. */
    def url(path: String): String

    /** Makes a request of the part of the extension that serves `endpoint`. */
    def call[Req, Res](endpoint: Endpoint[Req, Res])(request: Req): IO[Res]

  /** The values that the extension keeps. */
  trait Storage:

    /** The value of `key`. */
    def get[A](key: StorageKey[A]): IO[A]

    /** Gives `key` the value `value`. */
    def set[A](key: StorageKey[A], value: A): IO[Unit]

    /** Returns `key` to its default value. */
    def remove[A](key: StorageKey[A]): IO[Unit]

    /** Each new value that `key` is given. */
    def changes[A](key: StorageKey[A]): Stream[IO, A]

    /**
      * Transforms the value of `key`, and gives the new value. An update that
      * another part of the extension makes meanwhile may be lost.
      */
    final def update[A](key: StorageKey[A])(transform: A => A): IO[A] = get(key)
      .map(transform)
      .flatTap(set(key, _))

    /** The value of `key`, then each new value that it is given. */
    final def values[A](key: StorageKey[A]): Stream[IO, A] =
      Stream.eval(get(key)) ++ changes(key)

  /** The browser's tabs. */
  trait Tabs:

    /** Every open tab, of which Firefox for Android may give only some. */
    def all: IO[List[Tab]]

    /** The active tab of the current window, if there is one. */
    def active: IO[Option[Tab]]

    /** Opens a new tab at `url`. */
    def open(url: String): IO[Tab]

    /**
      * Makes a request of the content scripts of `tab` that serve `endpoint`,
      * which fails if none of them does.
      */
    def call[Req, Res]
      (
        endpoint: Endpoint.Tab[Req, Res],
        tab: TabId,
      )
      (request: Req)
      : IO[Res]

    /**
      * The value of `shared` in `tab`, which is its default unless a content
      * script there shares it.
      */
    def get[A](shared: Shared.Tab[A], tab: TabId): IO[A]

    /**
      * The value of `shared` in `tab`, then each new value: its default
      * whenever nothing there shares it, as before a content script there
      * begins sharing it, after it stops, or while the tab loads another page,
      * and otherwise the value that the content script shares. It follows the
      * tab from page to page, and ends with the default once the tab closes.
      *
      * Chrome may stop the background while it watches, as an open port does
      * not keep it running, so a page, such as the popup, is the place to
      * watch. Firefox may keep a port to a page in its back-forward cache open,
      * so the value there is that page's last until it returns, or until
      * another page in the tab begins sharing values.
      */
    def values[A](shared: Shared.Tab[A], tab: TabId): Stream[IO, A]

  /** The extension's toolbar button. */
  trait Action:

    /** Shows `text` on the button, or nothing if it is empty. */
    def setBadge(text: String): IO[Unit]

    /**
      * Shows `text` on the button while `tab` is active, in place of the text
      * shown in every other tab, until the tab closes or navigates.
      */
    def setBadge(text: String, tab: TabId): IO[Unit]

    /** Colours the badge in `colour`, as a CSS colour such as `#4688F1`. */
    def setBadgeColour(colour: String): IO[Unit]

    /** Colours the badge in `colour` while `tab` is active. */
    def setBadgeColour(colour: String, tab: TabId): IO[Unit]

  /**
    * The optional permissions, declared in
    * [[BrowserExtension.optionalPermissions]] and
    * [[BrowserExtension.optionalHosts]], that the user may grant the extension
    * while it runs, and take back.
    */
  trait Grants:

    /**
      * Asks the user for the permissions `Q`, and gives evidence of them if the
      * user grants them, as the APIs that they cover require. The browser asks
      * only in response to the user's input, so this must run from the handler
      * of it, through [[ContentBrowser.run]]. Firefox for Android has it from
      * version 120.
      */
    def request[Q : Permissions]: IO[Option[Granted[Q]]]

    /** Evidence of the permissions `Q`, if the extension has them. */
    def granted[Q : Permissions]: IO[Option[Granted[Q]]]

    /** Gives up the permissions `Q`, if they are optional. */
    def revoke[Q : Permissions]: IO[Unit]

    /**
      * Asks the user for access to the pages that `patterns` match, and gives
      * whether the user granted it, as [[request]] does.
      */
    def requestHosts(patterns: String*): IO[Boolean]

    /** Whether the extension may access the pages that `patterns` match. */
    def hasHosts(patterns: String*): IO[Boolean]

/** The ID of a [[Tab]], unique within the browser's session. */
opaque type TabId = Int

object TabId:

  def apply(id: Int): TabId = id

  extension (id: TabId) def toInt: Int = id

/**
  * A tab of the browser. Its `url` and `title` are known only for pages that
  * the extension may access, unless it has the [[Permission.Tabs]] permission.
  */
final case class Tab
  (
    id: TabId,
    windowId: Int,
    active: Boolean,
    url: Option[String],
    title: Option[String],
  )

/** A browser that an extension may be built for. */
enum Target(private[omnibrowser] val id: String):

  case Chrome extends Target("chrome")

  case Firefox extends Target("firefox")

object Target:

  private[omnibrowser] def parse(id: String): Option[Target] =
    values.find(_.id == id)
