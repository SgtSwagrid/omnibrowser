package com.alecdorrington.omnibrowser.launch

import scala.scalajs.js

/** Where each file of a built extension is, relative to its root. */
private[omnibrowser] object Layout:

  val manifest = "manifest.json"

  /** The linked bundle, which every part of the extension loads. */
  val bundle = "omnibrowser.js"

  /** The background's bootstrap. */
  val background = "background.js"

  /** The bootstrap of the content script at `index`. */
  def contentScript(index: Int): String = s"content-${ index + 1 }.js"

  def page(kind: PageKind): String = s"${ kind.id }.html"

  /** The bootstrap of the page of `kind`. */
  def pageScript(kind: PageKind): String = s"${ kind.id }.js"

/** The HTML of an extension's pages, which load the bundle. */
private[omnibrowser] object Html:

  /** A page of `kind`, titled `title`. */
  def page(title: String, kind: PageKind): String = s"""<!doctype html>
       |<html>
       |  <head>
       |    <meta charset="utf-8">
       |    <title>${ escape(title) }</title>
       |  </head>
       |  <body>
       |    <script src="${ Layout.pageScript(kind) }"></script>
       |    <script src="${ Layout.bundle }"></script>
       |  </body>
       |</html>
       |""".stripMargin

  private def escape(text: String): String = text.flatMap:
    case '&'   => "&amp;"
    case '<'   => "&lt;"
    case '>'   => "&gt;"
    case '"'   => "&quot;"
    case other => other.toString

/** JSON, as the browser writes it. */
private[omnibrowser] object Json:

  /** `value` as JSON, indented by two spaces. */
  def stringify(value: js.Any): String = js
    .Dynamic
    .global
    .JSON
    .stringify(value, null, 2)
    .asInstanceOf[String]
