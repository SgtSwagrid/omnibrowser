package com.alecdorrington.omnibrowser

/**
  * Values that one part of an extension owns, and shares with the others, which
  * see each new value as the owner gives it.
  */
object Shared:

  /**
    * A value that the content scripts of each tab may share, through
    * [[TabBrowser.share]], under `name`, which the background and pages read by
    * tab, through [[Browser.Tabs.get]] and [[Browser.Tabs.values]]. While
    * nothing in a tab shares it, its value there is `default`. Its name must be
    * unique among the extension's shared values.
    */
  final class Tab[A] private[omnibrowser] (val name: String, val default: A)
    (using val codec: Codec[A]):

    /**
      * The endpoint through which the background and pages ask a page for the
      * value of a shared value, which they name in the request.
      */
    private[omnibrowser] val reading: Endpoint[String, A] =
      Endpoint(Shared.reading)

  /** A value that the content scripts of each tab may share. */
  def tab[A : Codec](name: String, default: A): Tab[A] = new Tab(name, default)

  /** The name of the endpoint through which a page's shared values are read. */
  private[omnibrowser] val reading = "omnibrowser/shared"

  /**
    * The endpoint through which a page tells the background and pages that it
    * has begun sharing values, so that their watchers connect to it again.
    */
  private[omnibrowser] val announcement =
    Endpoint[Unit, Unit]("omnibrowser/sharing")

  /** The name of a port through which a page's value of `name` is watched. */
  private[omnibrowser] def port(name: String): String = s"$reading/$name"

  /** The name of the shared value that a port named `port` watches, if any. */
  private[omnibrowser] def watched(port: String): Option[String] =
    Option.when(port.startsWith(s"$reading/"))(port.drop(reading.length + 1))
