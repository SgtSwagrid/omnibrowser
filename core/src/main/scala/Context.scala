package com.alecdorrington.omnibrowser

import cats.effect.IO

/**
  * The background of an extension: the handlers of the events it responds to
  * while no page of it need be open.
  *
  * The browser starts the background when one of these events happens, and
  * stops it again once it is idle, so it keeps nothing in memory between
  * events. What it must remember belongs in [[Browser.Storage]].
  */
final case class Background(handlers: Handler*)

/** Something that an extension's [[Background]] does when an event happens. */
sealed trait Handler

object Handler:

  final case class Installed(run: (InstallReason, Browser) => IO[Unit])
    extends Handler

  final case class Startup(run: Browser => IO[Unit]) extends Handler

  final case class ActionClicked(run: (Tab, Browser) => IO[Unit])
    extends Handler

  final case class Serve[Req, Res]
    (
      endpoint: Endpoint[Req, Res],
      run: (Req, Sender, Browser) => IO[Res],
    )
    extends Handler

/**
  * Who made a request of an [[Endpoint]]: a content script, in the `tab` of its
  * page, or another part of the extension, at `url`.
  */
final case class Sender(tab: Option[Tab], url: Option[String])

/** Why an extension was installed. */
enum InstallReason:

  /** It was installed for the first time. */
  case Install

  /** It was updated to a new version. */
  case Update

  /** The browser was updated to a new version. */
  case BrowserUpdate

object InstallReason:

  private[omnibrowser] def parse(reason: String): InstallReason = reason match
    case "install"                          => Install
    case "chrome_update" | "browser_update" => BrowserUpdate
    case _                                  => Update

/**
  * A script that the browser injects into each page that one of `matches`
  * matches, at `runAt`, with access to only some of the browser's APIs, along
  * with the stylesheets `css`, as paths among the extension's assets.
  */
final case class ContentScript
  (
    matches: Seq[String],
    runAt: RunAt,
    run: TabBrowser => IO[Unit],
    css: Seq[String] = Seq.empty,
  ):

  /** This script, injected at `runAt` instead. */
  def at(runAt: RunAt): ContentScript = copy(runAt = runAt)

  /**
    * This script, with the stylesheets at `paths` among the extension's assets
    * injected into its pages too, before the page's DOM is built.
    */
  def withCss(paths: String*): ContentScript = copy(css = css ++ paths)

object ContentScript:

  /**
    * A script injected into each page that one of `matches` matches, once the
    * page has loaded. Each is a
    * [[https://developer.mozilla.org/docs/Mozilla/Add-ons/WebExtensions/Match_patterns match pattern]].
    */
  def apply(matches: String*)(run: TabBrowser => IO[Unit]): ContentScript =
    ContentScript(matches, RunAt.Idle, run)

/** When the browser injects a [[ContentScript]] into its page. */
enum RunAt(private[omnibrowser] val id: String):

  /** Before the page's DOM is built. */
  case Start extends RunAt("document_start")

  /** Once the page's DOM is built, before its images and frames have loaded. */
  case End extends RunAt("document_end")

  /** Once the page has loaded, or soon after. */
  case Idle extends RunAt("document_idle")

/**
  * A page of the extension, whose body begins as the HTML `body`, styled by
  * `stylesheets`, as paths among the extension's assets, and which `run` fills
  * in once it has loaded.
  */
final case class Page
  (
    run: Browser => IO[Unit],
    body: String = "",
    stylesheets: Seq[String] = Seq.empty,
  ):

  /** This page, whose body begins as the HTML `html`. */
  def withBody(html: String): Page = copy(body = html)

  /** This page, styled by the stylesheets at `paths` too. */
  def withStylesheets(paths: String*): Page =
    copy(stylesheets = stylesheets ++ paths)
