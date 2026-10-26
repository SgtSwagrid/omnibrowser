package com.alecdorrington.omnibrowser.testkit

import scala.scalajs.js

/**
  * The documents of a [[FakeBrowser]]'s tabs and pages: jsdom's, if the tests
  * can load it, as from their `node_modules`, or else stand-ins with only a
  * body of text. jsdom has no `innerText`, so its elements are given one that
  * is the same as their `textContent`.
  */
private[testkit] object Dom:

  /** jsdom, if the tests can load it. */
  private lazy val jsdom: Option[js.Dynamic] = Node.load("jsdom")

  /**
    * The window to which the documents belong as the extension runs within the
    * test, which is the test's `window`, and whose interfaces, such as
    * `HTMLElement`, are the test's too, so that the extension's code finds each
    * document's elements to be what they are.
    */
  private lazy val shared: Option[js.Dynamic] =
    window("about:blank", "").map(share)

  /** The document of a tab at `url` whose body holds `text`, within the test. */
  def tab(url: String, text: String): js.Dynamic = shared.fold(
    FakeDocument(text, url).asInstanceOf[js.Dynamic],
  )(window => fill(blank(window, url), text))

  /**
    * The document of a page titled `title`, whose body begins as the HTML
    * `body`, within the test.
    */
  def page(title: String, body: String): js.Dynamic =
    shared.fold(FakeDocument("", title).asInstanceOf[js.Dynamic]): window =>
      val document = blank(window, title)
      document.body.innerHTML = body
      document

  /** A frame of its own, for a tab at `url` whose body holds `text`. */
  def tabFrame(url: String, text: String): Frame =
    window(url, "").fold(Frame.standIn(FakeDocument(text, url))): window =>
      window.document.title = url
      Frame(fill(window.document, text), window)

  /**
    * A frame of its own, for a page at `url` whose document is the HTML `html`,
    * or else a stand-in titled `title`.
    */
  def pageFrame(url: String, title: String, html: String): Frame =
    window(url, html).fold(Frame.standIn(FakeDocument("", title)))(window =>
      Frame(window.document, window),
    )

  /**
    * A window of jsdom at `url`, whose document is the HTML `html`, and which
    * is a global scope of its own, in which scripts may run.
    */
  private def window(url: String, html: String): Option[js.Dynamic] =
    jsdom.map: jsdom =>
      val options = js.Dynamic.literal(url = url, runScripts = "outside-only")
      val window  = js
        .Dynamic
        .newInstance(jsdom.JSDOM)(html, options)
        .getInternalVMContext()
      imitateInnerText(window)
      window

  private def blank(window: js.Dynamic, title: String): js.Dynamic = window
    .document
    .implementation
    .createHTMLDocument(title)

  private def fill(document: js.Dynamic, text: String): js.Dynamic =
    document.body.textContent = text
    document

  /**
    * Makes `window`, and each of its interfaces that the test lacks, a global
    * of the test.
    */
  private def share(window: js.Dynamic): js.Dynamic =
    val global = js.Dynamic.global.globalThis
    val names  = js
      .Object
      .getOwnPropertyNames(window.asInstanceOf[js.Object])
      .filter(name => name.headOption.exists(_.isUpper) || name == "window")
      .filter(name => js.isUndefined(global.selectDynamic(name)))
    names.foreach(name =>
      global.updateDynamic(name)(window.selectDynamic(name)),
    )
    window

  /** Gives the elements of `window` an `innerText`, if they have none. */
  private def imitateInnerText(window: js.Dynamic): Unit =
    val prototype = window.HTMLElement.prototype.asInstanceOf[js.Object]
    if !js.Object.hasProperty(prototype, "innerText") then
      js.Object.defineProperty(prototype, "innerText", innerText)

  /** An `innerText` that is the same as the element's `textContent`. */
  private val innerText: js.PropertyDescriptor =
    val get: js.ThisFunction0[js.Dynamic, js.Any]       = _.textContent
    val set: js.ThisFunction1[js.Dynamic, js.Any, Unit] =
      (element, text) => element.textContent = text
    js.Dynamic
      .literal(get = get, set = set, configurable = true)
      .asInstanceOf[js.PropertyDescriptor]

/**
  * The `document` of a tab or page of a built extension, and the global scope,
  * of its own, in which the extension's scripts run on it.
  */
private[testkit] final case class Frame(document: js.Dynamic, scope: js.Dynamic)

private[testkit] object Frame:

  /** A frame of the stand-in `document`, in a scope with no DOM. */
  def standIn(document: FakeDocument): Frame = Frame(
    document.asInstanceOf[js.Dynamic],
    Node.vm.createContext(js.Dynamic.literal(document = document)),
  )

  /** A scope of its own, with no document, as the background's is. */
  def empty: js.Dynamic = Node.vm.createContext(js.Object())
