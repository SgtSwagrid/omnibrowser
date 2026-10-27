package com.alecdorrington.omnibrowser.launch

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.alecdorrington.omnibrowser.*
import com.alecdorrington.omnibrowser.webext.{
  MessageListener, Messages, WebExtensionApi, WebExtensionTabs,
}

/**
  * Starts each part of an extension in the browser, starting each program that
  * a part runs with `launch`, which does not wait for it to finish.
  */
private[omnibrowser] final class Launcher(val launch: IO[Unit] => Unit):

  /**
    * Registers the handlers of the extension's background. The browser gives an
    * event only to the listeners registered when the background starts, so they
    * are registered at once, before anything else happens.
    */
  def background
    (
      extension: BrowserExtension[?],
      browser: => Browser,
      api: WebExtensionApi,
    )
    : Unit = extension.background().handlers.foreach(register(api, browser))

  /** Runs a content script in the page into which it was injected. */
  def contentScript
    (
      script: ContentScript,
      browser: TabBrowser,
    )
    : Unit = launch(script.run(browser))

  /** Fills in a page of the extension. */
  def page(page: Page, browser: Browser): Unit = launch(page.run(browser))

  private def register
    (api: WebExtensionApi, browser: => Browser)
    (handler: Handler)
    : Unit = handler match
    case Handler.Installed(run) => api
        .runtime
        .onInstalled
        .addListener(details =>
          launch(run(
            InstallReason.parse(details.reason),
            browser,
          )),
        )
    case Handler.Startup(run) =>
      api.runtime.onStartup.addListener(() => launch(run(browser)))
    case Handler.ActionClicked(run) => api
        .action
        .onClicked
        .addListener(tab => launch(run(WebExtensionTabs.tab(tab), browser)))
    case serve: Handler.Serve[?, ?] =>
      api.runtime.onMessage.addListener(listener(serve, browser))

  /**
    * A listener that answers the requests sent as `serve`'s message, and leaves
    * every other message to the other listeners.
    */
  private def listener[Req, Res]
    (
      serve: Handler.Serve[Req, Res],
      browser: => Browser,
    )
    : MessageListener =
    Messages.listener(serve.message, launch)(serve.run(_, _, browser))

private[omnibrowser] object Launcher:

  /**
    * Starts each program at once, running it without pausing until its first
    * asynchronous step, and the rest without waiting, reporting any failure to
    * the console.
    */
  val default: Launcher = Launcher(program =>
    program
      .handleErrorWith(report)
      .syncStep(Int.MaxValue)
      .unsafeRunSync()
      .left
      .foreach(_.unsafeRunAndForget()),
  )

  private def report(error: Throwable): IO[Unit] =
    IO(global.compute.reportFailure(error))
