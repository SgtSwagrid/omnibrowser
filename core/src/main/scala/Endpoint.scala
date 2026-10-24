package com.alecdorrington.omnibrowser

/**
  * A named channel through which one part of an extension makes requests of
  * another, which [[BrowserExtension.serve]] answers. Its name must be unique
  * within the extension.
  */
final class Endpoint[Req, Res]
  (val name: String)
  (
    using val request: Codec[Req],
    val response: Codec[Res],
  )

/** A failure to make a request through the endpoint named `endpoint`. */
final case class MessagingError(endpoint: String, reason: String)
  extends Exception(s"The endpoint $endpoint failed: $reason")
