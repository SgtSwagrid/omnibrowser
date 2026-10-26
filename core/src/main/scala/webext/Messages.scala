package com.alecdorrington.omnibrowser.webext

import cats.effect.IO
import cats.syntax.all.*
import com.alecdorrington.omnibrowser.{
  Codec, Endpoint, MessagingError, Sender, Shared,
}
import com.alecdorrington.omnibrowser.webext.Interop.promise
import scala.scalajs.js

/**
  * The messages through which an extension's parts make requests of each other,
  * and share values with each other, which the browser serialises as JSON.
  */
private[omnibrowser] object Messages:

  /**
    * Makes the request `request` of `endpoint`, sending it through `send`, and
    * gives the response.
    */
  def exchange[Req, Res]
    (
      endpoint: Endpoint[Req, Res],
      request: Req,
    )
    (send: js.Any => js.Promise[js.Any])
    : IO[Res] = promise(send(Messages.request(endpoint, request)))
    .adaptError { case js.JavaScriptException(error) =>
      MessagingError(endpoint.name, error.toString)
    }
    .flatMap(response(endpoint, _))

  /**
    * A listener that answers the requests of `endpoint` through `run`, which
    * `launch` starts, and leaves every other message to the other listeners.
    */
  def listener[Req, Res]
    (
      endpoint: Endpoint[Req, Res],
      launch: IO[Unit] => Unit,
    )
    (run: (Req, Sender) => IO[Res])
    : MessageListener = (message, sender, respond) =>
    val mine = Messages.endpoint(message).contains(endpoint.name)
    if mine then
      launch(
        answer(
          endpoint,
          payload(message),
          Messages.sender(sender),
        )(run).map(respond(_)),
      )
    mine

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

  /** Whether `message` answers a request, as no response does if none came. */
  def isAnswer(message: js.Any): Boolean = hasField(message, "response") ||
    hasField(message, "error")

  /** The response that `message` holds to a request of `endpoint`. */
  def response[Req, Res]
    (
      endpoint: Endpoint[Req, Res],
      message: js.Any,
    )
    : IO[Res] = IO.fromEither(
    decode(endpoint.response, message).leftMap(MessagingError(endpoint.name, _)),
  )

  /** A message through a port, holding a new value. */
  def value(json: js.Any): js.Any = js.Dynamic.literal(value = json)

  /** The value of `shared` that `message`, from a port, holds. */
  def value[A](shared: Shared.Tab[A], message: js.Any): IO[A] = IO.fromEither(
    field(message, "value")
      .toRight("The message holds no value.")
      .flatMap(shared.codec.decode)
      .leftMap(MessagingError(shared.name, _)),
  )

  private def answer[Req, Res]
    (
      endpoint: Endpoint[Req, Res],
      request: js.Any,
      sender: Sender,
    )
    (run: (Req, Sender) => IO[Res])
    : IO[js.Any] = IO
    .fromEither(
      endpoint.request.decode(request).leftMap(MessagingError(endpoint.name, _)),
    )
    .flatMap(run(_, sender))
    .map(response => success(endpoint.response.encode(response)))
    .handleError(error => failure(error.getMessage))

  private def decode[Res]
    (codec: Codec[Res], message: js.Any)
    : Either[String, Res] =
    (field(message, "response"), field(message, "error")) match
      case (Some(response), _) => codec.decode(response)
      case (None, Some(error)) => Left(error.toString)
      case (None, None)        => Left("Nothing in the extension serves it.")

  private def field(message: js.Any, name: String): Option[js.Any] = Option
    .when(hasField(message, name))(
      message.asInstanceOf[js.Dynamic].selectDynamic(name),
    )

  private def isObject(message: js.Any): Boolean =
    js.typeOf(message) == "object" && message != null

  private def hasField(message: js.Any, name: String): Boolean =
    isObject(message) && message.asInstanceOf[js.Object].hasOwnProperty(name)
