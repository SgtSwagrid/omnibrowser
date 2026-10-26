package com.alecdorrington.omnibrowser

/**
  * Values that one part of an extension owns, and shares with the others, which
  * see each new value that the owner gives it.
  */
object Shared:

  /**
    * A value that the content scripts of each tab may share, through
    * [[TabBrowser.share]], under `name`, which the background and pages read by
    * tab, through [[Browser.Tabs.get]] and [[Browser.Tabs.values]]. While
    * nothing in a tab shares it, its value there is `default`. Its name must be
    * unique within the extension.
    */
  final class Tab[A] private[omnibrowser] (val name: String, val default: A)
    (using val codec: Codec[A]):

    /** The endpoint through which the background and pages ask for the value. */
    private[omnibrowser] val endpoint: Endpoint[Unit, A] = Endpoint(name)

  /** A value that the content scripts of each tab may share. */
  def tab[A : Codec](name: String, default: A): Tab[A] = new Tab(name, default)
