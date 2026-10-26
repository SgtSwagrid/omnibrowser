package com.alecdorrington.omnibrowser.webext

import cats.effect.IO
import cats.syntax.all.*
import com.alecdorrington.omnibrowser.{Codec, Endpoint, MessagingError, Sender}
import scala.scalajs.js

/**
  * The messages through which an extension's parts make requests of each other,
  * which the browser serialises as JSON.
  */
private[omnibrowser] object Messages:

  /** A request of `endpoint`. */
  def request[Req, Res]
    (
      endpoint: Endpoint[Req, Res],
      request: Req,
    )
    : js.Any = js
    .Dynamic
    .literal(
      omnibrowser = endpoint.name,
      request = endpoint.request.encode(request),
    )

  /** The name of the endpoint that `message` is a request of, if it is one. */
  def endpoint(message: js.Any): Option[String] = field(message, "omnibrowser")
    .filter(js.typeOf(_) == "string")
    .map(_.asInstanceOf[String])

  /** Who made a request, as the browser describes them as `sender`. */
  def sender(sender: js.Any): Sender = Sender(
    tab = field(sender, "tab").map(tab =>
      WebExtensionTabs.tab(tab.asInstanceOf[TabApi]),
    ),
    url = field(sender, "url")
      .filter(js.typeOf(_) == "string")
      .map(_.asInstanceOf[String]),
  )

  /** What a request asks for. */
  def payload(message: js.Any): js.Any =
    field(message, "request").getOrElse(js.undefined)

  /** A response holding `response`. */
  def success(response: js.Any): js.Any = js
    .Dynamic
    .literal(response = response)

  /** A response saying why a request failed. */
  def failure(error: String): js.Any = js.Dynamic.literal(error = error)

  /** The response that `message` holds to a request of `endpoint`. */
  def response[Req, Res]
    (
      endpoint: Endpoint[Req, Res],
      message: js.Any,
    )
    : IO[Res] = IO.fromEither(
    decode(endpoint.response, message).leftMap(MessagingError(endpoint.name, _)),
  )

  private def decode[Res]
    (codec: Codec[Res], message: js.Any)
    : Either[String, Res] =
    (field(message, "response"), field(message, "error")) match
      case (Some(response), _) => codec.decode(response)
      case (None, Some(error)) => Left(error.toString)
      case (None, None)        => Left("Nothing in the extension serves it.")

  private def field(message: js.Any, name: String): Option[js.Any] = Option
    .when(isObject(message) && hasField(message, name))(
      message.asInstanceOf[js.Dynamic].selectDynamic(name),
    )

  private def isObject(message: js.Any): Boolean =
    js.typeOf(message) == "object" && message != null

  private def hasField(message: js.Any, name: String): Boolean = message
    .asInstanceOf[js.Object]
    .hasOwnProperty(name)
