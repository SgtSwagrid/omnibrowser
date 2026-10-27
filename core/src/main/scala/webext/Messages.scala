package com.alecdorrington.omnibrowser.webext

import cats.effect.IO
import cats.syntax.all.*
import com.alecdorrington.omnibrowser.{
  Codec, Message, MessagingError, Sender, Shared,
}
import com.alecdorrington.omnibrowser.webext.Interop.promise
import scala.scalajs.js

/**
  * The messages through which an extension's parts make requests of each other,
  * and share values with each other, which the browser serialises as JSON.
  */
private[omnibrowser] object Messages:

  /** Sends `request` as `message`, through `send`, and gives the response. */
  def exchange[Req, Res]
    (message: Message[Req, Res], request: Req)
    (send: js.Any => js.Promise[js.Any])
    : IO[Res] = promise(send(Messages.request(message, request)))
    .adaptError { case js.JavaScriptException(error) =>
      MessagingError(message.name, error.toString)
    }
    .flatMap(response(message, _))

  /**
    * A listener that answers the requests sent as `message` through `run`,
    * which `launch` starts, and leaves every other message to the other
    * listeners.
    */
  def listener[Req, Res]
    (
      message: Message[Req, Res],
      launch: IO[Unit] => Unit,
    )
    (run: (Req, Sender) => IO[Res])
    : MessageListener = (json, sender, respond) =>
    val mine = Messages.name(json).contains(message.name)
    if mine then
      launch(
        answer(
          message,
          payload(json),
          Messages.sender(sender),
        )(run).map(respond(_)),
      )
    mine

  /** A request sent as `message`. */
  def request[Req, Res](message: Message[Req, Res], request: Req): js.Any = js
    .Dynamic
    .literal(
      omnibrowser = message.name,
      request = message.request.encode(request),
    )

  /** The name of the message that `json` is a request sent as, if it is one. */
  def name(json: js.Any): Option[String] = field(json, "omnibrowser")
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
  def payload(json: js.Any): js.Any =
    field(json, "request").getOrElse(js.undefined)

  /** A response holding `response`. */
  def success(response: js.Any): js.Any = js
    .Dynamic
    .literal(response = response)

  /** A response saying why a request failed. */
  def failure(error: String): js.Any = js.Dynamic.literal(error = error)

  /** Whether `json` answers a request, as no response does if none came. */
  def isAnswer(json: js.Any): Boolean = hasField(json, "response") ||
    hasField(json, "error")

  /** The response that `json` holds to a request sent as `message`. */
  def response[Req, Res](message: Message[Req, Res], json: js.Any): IO[Res] = IO
    .fromEither(
      decode(message.response, json).leftMap(MessagingError(message.name, _)),
    )

  /** A message through a port, holding a new value. */
  def value(json: js.Any): js.Any = js.Dynamic.literal(value = json)

  /** A message through a port, saying that nothing shares its value now. */
  def unshared: js.Any = js.Dynamic.literal(unshared = true)

  /**
    * The value of `shared` that `json`, from a port, holds, or nothing if it
    * says that nothing shares it.
    */
  def watched[A](shared: Shared.Tab[A], json: js.Any): IO[Option[A]] = IO
    .fromEither(
      field(json, "value")
        .map(shared.codec.decode(_).map(Some(_)))
        .orElse(Option.when(hasField(json, "unshared"))(Right(None)))
        .getOrElse(Left("The message holds no value."))
        .leftMap(MessagingError(shared.name, _)),
    )

  private def answer[Req, Res]
    (
      message: Message[Req, Res],
      request: js.Any,
      sender: Sender,
    )
    (run: (Req, Sender) => IO[Res])
    : IO[js.Any] = IO
    .fromEither(
      message.request.decode(request).leftMap(MessagingError(message.name, _)),
    )
    .flatMap(run(_, sender))
    .map(response => success(message.response.encode(response)))
    .handleError(error => failure(reason(error)))

  /**
    * Why `error` happened: a messaging error's reason, or else what its message
    * says, or else its name.
    */
  def reason(error: Throwable): String = error match
    case MessagingError(_, reason) => reason
    case other => Option(other.getMessage).getOrElse(other.toString)

  private def decode[Res]
    (codec: Codec[Res], json: js.Any)
    : Either[String, Res] =
    (field(json, "response"), field(json, "error")) match
      case (Some(response), _) => codec.decode(response)
      case (None, Some(error)) => Left(stated(error))
      case (None, None)        => Left("Nothing in the extension serves it.")

  /** Why a request failed, as its response says. */
  private def stated(error: js.Any): String =
    if Codec.isNull(error) then "It failed for no stated reason."
    else error.toString

  private def field(json: js.Any, name: String): Option[js.Any] = Option.when(
    hasField(json, name),
  )(json.asInstanceOf[js.Dynamic].selectDynamic(name))

  private def isObject(json: js.Any): Boolean = js.typeOf(json) == "object" &&
    json != null

  private def hasField(json: js.Any, name: String): Boolean = isObject(json) &&
    json.asInstanceOf[js.Object].hasOwnProperty(name)
