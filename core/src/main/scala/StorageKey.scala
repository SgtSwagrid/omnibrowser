package com.alecdorrington.omnibrowser

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
  * `area`, which is `default` until it is first set.
  */
final class StorageKey[A]
  (
    val area: StorageArea,
    val name: String,
    val default: A,
  )
  (using val codec: Codec[A])

object StorageKey:

  /** A value kept on this device. */
  def local[A : Codec](name: String, default: A): StorageKey[A] =
    StorageKey(StorageArea.Local, name, default)

  /** A value kept on every device to which the user signs in. */
  def sync[A : Codec](name: String, default: A): StorageKey[A] =
    StorageKey(StorageArea.Sync, name, default)

  /** A value kept in memory until the browser closes. */
  def session[A : Codec](name: String, default: A): StorageKey[A] =
    StorageKey(StorageArea.Session, name, default)

/** A stored value of the key named `key`, which its codec cannot decode. */
final case class StorageError(key: String, reason: String)
  extends Exception(s"The stored value of $key is invalid: $reason")
