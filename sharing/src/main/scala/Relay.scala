package com.alecdorrington.omnibrowser.sharing

import cats.effect.{Deferred, IO}
import cats.syntax.all.*
import com.alecdorrington.omnibrowser.*
import com.alecdorrington.omnibrowser.Permission.Storage
import fs2.concurrent.Signal

/**
  * Relays a count from each secure page to the popup, which records each count
  * of the active tab that it sees. Some pages have two content scripts that
  * share the count, so that the tests see how they share it as the plugin
  * builds them, each with its own copy of the content bundle.
  */
object Relay extends BrowserExtension[Storage]:

  override val name = "Relay"

  /** The count of a page. */
  val count = Shared.tab[Int]("count", 0)

  /** Has a page's second script begin sharing its count. */
  val begin = Endpoint.tab[Unit, Unit]("begin")

  /** Has a page's second script stop sharing its count. */
  val stop = Endpoint.tab[Unit, Unit]("stop")

  /** Each count of the active tab that the popup has seen, latest first. */
  val seen = StorageKey.local[List[Int]]("seen", Nil)

  override def contentScripts() = Seq(
    // A page at `/both` shares `1` from the start.
    ContentScript("https://*/both")(
      _.share(count)(Signal.constant(1)).useForever,
    ),
    // Every secure page shares `7` once asked, until it is asked to stop.
    ContentScript("https://*/*")(browser =>
      (Deferred[IO, Unit], Deferred[IO, Unit]).flatMapN((begun, stopped) =>
        (browser.serve(begin)(_ => begun.complete(()).void) >>
          browser.serve(stop)(_ => stopped.complete(()).void)).surround(
          begun.get >>
            browser.share(count)(Signal.constant(7)).surround(stopped.get),
        ),
      ),
    ),
  )

  override def popup() = Some(Page(browser =>
    browser
      .tabs
      .active
      .flatMap(_.traverse_(tab =>
        browser
          .tabs
          .values(count, tab.id)
          .evalMap(value => browser.storage.update(seen)(value :: _))
          .compile
          .drain,
      )),
  ))
