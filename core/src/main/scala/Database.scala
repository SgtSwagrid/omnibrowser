package com.alecdorrington.omnibrowser

import io.circe.{Codec, Decoder, Encoder, Json}
import scala.collection.mutable

/**
  * A database of the extension's in the browser's IndexedDB, named `name`, for
  * more than [[Browser.Storage]] holds, read and written through
  * [[Browser.Databases]]. Its object stores are those that the object extending
  * it makes with [[store]], each a `val` of it, so that every one is made
  * before the database is first opened:
  *
  * {{{
  * object Lexicon extends Database("lexicon", version = 1):
  *   val lexemes = store[Lexeme]("lexemes")
  *   val counts  = store[Int]("counts")
  * }}}
  *
  * The browser makes it with its stores the first time it is opened, and adds
  * the stores that it lacks whenever it is opened at a `version` above its own,
  * which must be raised whenever a store is added, and never lowered. A store
  * left out stays in the database, unused, with what it kept.
  *
  * Only the background and the extension's pages reach it: a content script's
  * IndexedDB is the web page's, not the extension's, so its browser has no
  * databases. No permission is needed. Without [[Permission.UnlimitedStorage]],
  * it has the browser's default quota, and the browser may evict it when the
  * device runs short of space.
  */
abstract class Database(val name: String, val version: Int):

  require(
    version >= 1,
    s"The database $name's version must be at least 1.",
  )

  /** The stores made so far, in order, of which only construction adds. */
  private val made = mutable.ListBuffer.empty[ObjectStore[?]]

  /** The database's object stores, in the order that they were made. */
  final def stores: List[ObjectStore[?]] = made.toList

  /**
    * The database's object store named `name`, which keeps values of type `A`
    * by their keys, each as JSON through circe's codecs.
    */
  protected final def store[A : {Encoder, Decoder}]
    (name: String)
    : ObjectStore[A] =
    val store = ObjectStore[A](this, name)
    made += store
    store

object Database:

  /**
    * A change to the values of a database's object store, which
    * [[Browser.Databases.write]] makes, along with others, all at once.
    */
  sealed trait Write:

    /** The store that it changes. */
    def store: ObjectStore[?]

  /** Keeps `json` under `key` in `store`, in place of what was there. */
  private[omnibrowser] final case class Put
    (
      store: ObjectStore[?],
      key: String,
      json: Json,
    )
    extends Write

  /** Takes out the value kept under `key` in `store`, if there is one. */
  private[omnibrowser] final case class Delete
    (store: ObjectStore[?], key: String)
    extends Write

  /** Takes out every value kept in `store`. */
  private[omnibrowser] final case class Clear(store: ObjectStore[?])
    extends Write

/**
  * An object store of `database`, named `name`, which keeps values of type `A`,
  * each as JSON, through circe's codecs, under a key of its own. Its keys are
  * ordered as strings are, by their UTF-16 code units. A [[Database]] makes its
  * own.
  */
final class ObjectStore[A : {Encoder, Decoder}] private[omnibrowser] (
  val database: Database,
  val name: String,
):

  /** The codec of its values. */
  val codec: Codec[A] = Codec.implied

  /** A write keeping `value` under `key`, in place of any value kept there. */
  def set(key: String, value: A): Database.Write =
    Database.Put(this, key, codec(value))

  /** A write taking out the value kept under `key`, if there is one. */
  def remove(key: String): Database.Write = Database.Delete(this, key)

  /** A write taking out every value kept in the store. */
  def clear: Database.Write = Database.Clear(this)

  override def toString: String = s"${ database.name }/$name"

/**
  * A failure of the database named `database`, as when it cannot be opened, or
  * a write cannot be kept, as when the browser has no room for it, for
  * `reason`, the browser's own where it gave one.
  */
final case class DatabaseError(database: String, reason: String)
  extends Exception(s"The database $database failed: $reason")
