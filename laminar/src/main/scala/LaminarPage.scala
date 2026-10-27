package com.alecdorrington.omnibrowser.laminar

import cats.effect.IO
import com.alecdorrington.omnibrowser.{Browser, ContentScript, Page, TabBrowser}
import com.raquo.laminar.api.L.{render, HtmlElement}
import org.scalajs.dom.document

/** A page of the extension, whose content Laminar renders. */
object LaminarPage:

  /**
    * A page into whose body Laminar renders the element that `content` makes,
    * once the page has loaded, after whatever the body begins with, as in
    * `LaminarPage(...).withStylesheets("popup.css")`.
    */
  def apply(content: Browser => HtmlElement): Page =
    Page(browser => renderInBody(content(browser)))

/** A content script that adds an element, which Laminar renders, to its page. */
object LaminarContentScript:

  /**
    * A script injected into each page that one of `matches` matches, once the
    * page has loaded, which renders the element that `content` makes at the end
    * of the page's body. Each is a match pattern, as in [[ContentScript]]. The
    * page's body must exist by the time that the script runs, so it may run at
    * [[com.alecdorrington.omnibrowser.RunAt.End]], but not before.
    */
  def apply
    (matches: String*)
    (content: TabBrowser => HtmlElement)
    : ContentScript =
    ContentScript(matches*)(browser => renderInBody(content(browser)))

/** Renders `element` at the end of the document's body. */
private def renderInBody(element: => HtmlElement): IO[Unit] =
  IO(render(document.body, element)).void
