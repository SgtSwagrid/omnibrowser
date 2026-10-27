package com.alecdorrington.omnibrowser.webext

import cats.syntax.all.*
import io.circe.{Decoder, DecodingFailure, Encoder, Json}
import io.circe.scalajs.convertJsonToJs
import scala.scalajs.js

/**
  * Circe's codecs, as between values and the JavaScript values in which
  * messages and storage hold them.
  */
private[omnibrowser] object Circe:

  extension [A](encoder: Encoder[A])

    /** The JavaScript value that holds `value`. */
    def encodeJs(value: A): js.Any = convertJsonToJs(encoder(value))

  extension [A](decoder: Decoder[A])

    /** The value that `json` holds, or why it holds none. */
    def decodeJs(json: js.Any): Either[String, A] =
      toJson(json).flatMap(decoder.decodeJson(_).leftMap(reason))

  /**
    * The JSON that `json` holds. Unlike circe-scalajs's `convertJsToJson`,
    * which tells objects and arrays by their classes, it knows those of any
    * realm, such as the testkit gives the sandboxes of a built extension.
    */
  private def toJson(json: js.Any): Either[String, Json] = js.typeOf(json) match
    case "string"    => Right(Json.fromString(json.asInstanceOf[String]))
    case "number"    => Right(Json.fromDoubleOrNull(json.asInstanceOf[Double]))
    case "boolean"   => Right(Json.fromBoolean(json.asInstanceOf[Boolean]))
    case "undefined" => Right(Json.Null)
    case "object" if json == null           => Right(Json.Null)
    case "object" if js.Array.isArray(json) => array(json)
    case "object"                           => fields(json)
    case kind => Left(s"Expected JSON, not a $kind.")

  private def array(json: js.Any): Either[String, Json] = json
    .asInstanceOf[js.Array[js.Any]]
    .toList
    .traverse(toJson)
    .map(Json.fromValues)

  private def fields(json: js.Any): Either[String, Json] = json
    .asInstanceOf[js.Dictionary[js.Any]]
    .toList
    .traverse((name, value) => toJson(value).map(name -> _))
    .map(Json.fromFields)

  /** Why a value could not be decoded, and where in it, if not at its root. */
  private def reason(failure: DecodingFailure): String = failure
    .pathToRootString
    .filter(_.nonEmpty)
    .fold(failure.message)(path => s"$path: ${ failure.message }")
