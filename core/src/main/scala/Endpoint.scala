package com.alecdorrington.omnibrowser

/**
  * A named channel through which one part of an extension makes requests of the
  * background, which [[BrowserExtension.serve]] answers. Its name must be
  * unique within the extension.
  */
final class Endpoint[Req, Res]
  (val name: String)
  (
    using val request: Codec[Req],
    val response: Codec[Res],
  )

object Endpoint:

  /**
    * A named channel through which the background and pages make requests of
    * the content scripts in a tab, through [[Browser.Tabs.call]], which they
    * answer through [[TabBrowser.serve]]. Its name must be unique within the
    * extension.
    */
  final class Tab[Req, Res] private[omnibrowser] (
    private[omnibrowser] val endpoint: Endpoint[Req, Res],
  ):

    def name: String = endpoint.name

  /** A channel through which to make requests of the content scripts in a tab. */
  def tab[Req : Codec, Res : Codec](name: String): Tab[Req, Res] =
    new Tab(Endpoint(name))

/** A failure to make a request through the endpoint named `endpoint`. */
final case class MessagingError(endpoint: String, reason: String)
  extends Exception(s"The endpoint $endpoint failed: $reason")
