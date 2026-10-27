package com.alecdorrington.omnibrowser

import io.circe.{Codec, Decoder, Encoder}

/** Where the browser keeps the value of a [[StorageKey]]. */
enum StorageArea(private[omnibrowser] val id: String):

  /** On this device, until the extension is removed. */
  case Local extends StorageArea("local")

  /**
    * On every device to which the user signs in, in small amounts. Firefox for
    * Android keeps the values on its own device only.
    */
  case Sync extends StorageArea("sync")

  /**
    * In memory, until the browser closes. Content scripts cannot see it unless
    * the background shares it with them.
    */
  case Session extends StorageArea("session")

/**
  * A value that the extension keeps in the browser's storage, under `name` in
  * `area`, which is `default` until it is first set. Its value is kept as JSON,
  * through circe's codecs. A stored value that they cannot decode, as one that
  * an earlier version stored differently, is `default` until it is set again,
  * and the browser's console warns of it.
  */
final class StorageKey[A : {Encoder, Decoder}]
  (
    val area: StorageArea,
    val name: String,
    val default: A,
  ):

  /** The codec of its values. */
  val codec: Codec[A] = Codec.implied

object StorageKey:

  /** A value kept on this device. */
  def local[A : {Encoder, Decoder}](name: String, default: A): StorageKey[A] =
    StorageKey(StorageArea.Local, name, default)

  /** A value kept on every device to which the user signs in. */
  def sync[A : {Encoder, Decoder}](name: String, default: A): StorageKey[A] =
    StorageKey(StorageArea.Sync, name, default)

  /** A value kept in memory until the browser closes. */
  def session[A : {Encoder, Decoder}](name: String, default: A): StorageKey[A] =
    StorageKey(StorageArea.Session, name, default)
