package com.alecdorrington.omnibrowser

/**
  * A kind of message that one part of an extension sends the background, which
  * [[BrowserExtension.serve]] answers. Its name must be unique among the
  * extension's messages, and must not begin with `omnibrowser/`, which the
  * library keeps for its own.
  */
final class Message[Req, Res]
  (val name: String)
  (
    using val request: Codec[Req],
    val response: Codec[Res],
  )

object Message:

  /**
    * A kind of message that the background and pages send the content scripts
    * in a tab, through [[Browser.Tabs.call]], which they answer through
    * [[TabBrowser.serve]]. Its name must be unique among the extension's
    * messages, and must not begin with `omnibrowser/`.
    */
  final class Tab[Req, Res] private[omnibrowser] (
    private[omnibrowser] val message: Message[Req, Res],
  ):

    def name: String = message.name

  /** A kind of message to send the content scripts in a tab. */
  def tab[Req : Codec, Res : Codec](name: String): Tab[Req, Res] =
    new Tab(Message(name))

/**
  * A failure to send or answer the message, or to read the shared value, named
  * `name`.
  */
final case class MessagingError(name: String, reason: String)
  extends Exception(s"The message $name failed: $reason")
