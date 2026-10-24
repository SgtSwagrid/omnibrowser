package com.alecdorrington.omnibrowser.example

import cats.effect.IO
import com.alecdorrington.omnibrowser.*
import com.alecdorrington.omnibrowser.Permission.Storage
import org.scalajs.dom.document

/**
  * Counts the words on each page that the user visits, and shows how many it
  * has counted in all.
  */
object WordCount extends BrowserExtension[Storage]:

  override val name = "Word Count"

  override val description = "Counts the words on the pages you visit."

  /** Counts the words in a page's text, and gives how many there were. */
  val count = Endpoint[String, Int]("count")

  /** How many words have been counted in all. */
  val total = StorageKey.local[Int]("total", 0)

  override val background = Background(
    serve(count): (text, browser) =>
      val words = text.split("\\s+").count(_.nonEmpty)
      browser
        .storage
        .update(total)(_ + words)
        .flatMap(sum => browser.action.setBadge(sum.toString))
        .as(words),
  )

  override val contentScripts = Seq(ContentScript("https://*/*")(browser =>
    browser.runtime.call(count)(document.body.innerText).void,
  ))

  override val popup = Some(Page(browser =>
    browser
      .storage
      .get(total)
      .flatMap(sum => IO(document.body.textContent = s"$sum words counted.")),
  ))
