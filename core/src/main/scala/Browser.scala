package com.alecdorrington.omnibrowser

import cats.effect.IO
import fs2.Stream

/** The browser, as a content script sees it: with only some of its APIs. */
trait ContentBrowser:

  /** The extension itself, and the messages between its parts. */
  def runtime: Browser.Runtime

  /** The values that the extension keeps. */
  def storage(using Granted[Permission.Storage]): Browser.Storage

/** The browser, as the extension's background and pages see it. */
trait Browser extends ContentBrowser:

  /** The browser's tabs. */
  def tabs: Browser.Tabs

  /** The extension's toolbar button. */
  def action: Browser.Action

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

    /** Every open tab. */
    def all: IO[List[Tab]]

    /** The active tab of the current window, if there is one. */
    def active: IO[Option[Tab]]

    /** Opens a new tab at `url`. */
    def open(url: String): IO[Tab]

  /** The extension's toolbar button. */
  trait Action:

    /** Shows `text` on the button, or nothing if it is empty. */
    def setBadge(text: String): IO[Unit]

    /** Colours the badge in `colour`, as a CSS colour such as `#4688F1`. */
    def setBadgeColour(colour: String): IO[Unit]

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
