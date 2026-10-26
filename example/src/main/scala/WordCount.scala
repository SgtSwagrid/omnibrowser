package com.alecdorrington.omnibrowser.example

import com.alecdorrington.omnibrowser.*
import com.alecdorrington.omnibrowser.Permission.Storage
import com.alecdorrington.omnibrowser.laminar.*
import com.raquo.laminar.api.L.*
import org.scalajs.dom.document

/**
  * Counts the words on each page that the user visits, and shows how many it
  * has counted in all, until the user resets the count.
  */
object WordCount extends BrowserExtension[Storage]:

  override val name = "Word Count"

  override val description = "Counts the words on the pages you visit."

  /** Counts the words in a page's text, and gives how many there were. */
  val count = Endpoint[String, Int]("count")

  /** Sets the count back to nothing. */
  val reset = Endpoint[Unit, Unit]("reset")

  /** How many words have been counted in all. */
  val total = StorageKey.local[Int]("total", 0)

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
    browser.runtime.call(count)(document.body.innerText).void,
  ))

  override def popup() = Some(LaminarPage(browser =>
    div(
      p(
        child.text <-- browser.signal(total).map(sum => s"$sum words counted."),
      ),
      button(
        "Reset",
        onClick --> browser.observer(_ => browser.runtime.call(reset)(())),
      ),
    ),
  ))
