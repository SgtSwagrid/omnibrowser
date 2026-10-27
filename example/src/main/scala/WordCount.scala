package com.alecdorrington.omnibrowser.example

import com.alecdorrington.omnibrowser.*
import com.alecdorrington.omnibrowser.Permission.Storage
import com.alecdorrington.omnibrowser.laminar.*
import com.raquo.laminar.api.L.*
import fs2.concurrent.Signal.constant
import org.scalajs.dom.document

/**
  * Counts the words on each page that the user visits, and shows how many it
  * has counted in all, until the user resets the count, and how many the page
  * in the active tab has.
  */
object WordCount extends BrowserExtension[Storage]:

  override val name = "Word Count"

  override val description = "Counts the words on the pages you visit."

  /** Counts the words in a page's text, and gives how many there were. */
  val count = Message[String, Int]("count")

  /** Sets the count back to nothing. */
  val reset = Message[Unit, Unit]("reset")

  /** How many words have been counted in all. */
  val total = StorageKey.local[Int]("total", 0)

  /** How many words a tab's page has, which its content script shares. */
  val words = Shared.tab[Int]("words", 0)

  /** A word, of letters and numbers in any script, as in `héllo` or `привет`. */
  private val word = """[\p{L}\p{N}']+""".r

  override def background() = Background(
    serve(count): (text, browser) =>
      val words = word.findAllIn(text).size
      browser
        .storage
        .update(total)(_ + words)
        .flatMap(sum => browser.action.setBadge(sum.toString))
        .as(words)
    ,
    serve(reset): (_, browser) =>
      browser.storage.set(total, 0) >> browser.action.setBadge(""),
  )

  override def contentScripts() = Seq(ContentScript("https://*/*")(browser =>
    browser
      .runtime
      .call(count)(document.body.innerText)
      .flatMap(counted => browser.share(words)(constant(counted)).useForever),
  ))

  override def popup() = Some(LaminarPage(browser =>
    div(
      p(
        child.text <-- browser.signal(total).map(sum => s"$sum words counted."),
      ),
      p(
        cls := "page",
        child.text <--
          browser
            .events(browser.tabs.active)
            .collect { case Some(tab) => tab.id }
            .flatMapSwitch(browser.signal(words, _))
            .map(counted => s"$counted on this page."),
      ),
      button(
        "Reset",
        onClick --> browser.observer(_ => browser.runtime.call(reset)(())),
      ),
    ),
  ))
