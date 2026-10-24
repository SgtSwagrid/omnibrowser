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
      run: (Req, Browser) => IO[Res],
    )
    extends Handler

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
  * matches, at `runAt`, with access to only some of the browser's APIs.
  */
final case class ContentScript
  (
    matches: Seq[String],
    runAt: RunAt,
    run: ContentBrowser => IO[Unit],
  ):

  /** This script, injected at `runAt` instead. */
  def at(runAt: RunAt): ContentScript = copy(runAt = runAt)

object ContentScript:

  /**
    * A script injected into each page that one of `matches` matches, once the
    * page has loaded. Each is a
    * [[https://developer.mozilla.org/docs/Mozilla/Add-ons/WebExtensions/Match_patterns match pattern]].
    */
  def apply(matches: String*)(run: ContentBrowser => IO[Unit]): ContentScript =
    ContentScript(matches, RunAt.Idle, run)

/** When the browser injects a [[ContentScript]] into its page. */
enum RunAt(private[omnibrowser] val id: String):

  /** Before the page's DOM is built. */
  case Start extends RunAt("document_start")

  /** Once the page's DOM is built, before its images and frames have loaded. */
  case End extends RunAt("document_end")

  /** Once the page has loaded, or soon after. */
  case Idle extends RunAt("document_idle")

/** A page of the extension, which `run` fills in once it has loaded. */
final case class Page(run: Browser => IO[Unit])
