package com.alecdorrington.omnibrowser.webext

import cats.effect.IO
import com.alecdorrington.omnibrowser.*
import com.alecdorrington.omnibrowser.webext.Circe.*
import com.alecdorrington.omnibrowser.webext.Interop.promise
import io.circe.scalajs.convertJsonToJs
import org.scalajs.dom.console
import scala.collection.immutable.SortedMap
import scala.collection.mutable
import scala.scalajs.js
import scala.util.Try
import scala.util.control.NonFatal

/**
  * The extension's databases, in the IndexedDB that `factory` gives, if the
  * browser has one there, as it does in the background and the extension's
  * pages.
  *
  * Each read or write is one transaction, which the browser ends, committing
  * it, as soon as it turns to another task with none of the transaction's
  * requests pending. Each step of a program may be such a turn, as cats-effect
  * runs a program's next step in a task of its own once it has waited, so no
  * transaction is held across steps: [[transaction]] makes the transaction and
  * its first requests all in the one callback of an `IO.async`, the requests'
  * own callbacks make any that follow, and the program resumes once the
  * transaction completes.
  */
private[omnibrowser] final class WebExtensionDatabases
  (factory: () => js.UndefOr[IdbFactoryApi])
  extends Browser.Databases:

  /**
    * The connection to each database, as it is being opened or once it is open,
    * until it closes. Each is opened only once here, however many ask for it at
    * once.
    */
  private val connections = mutable
    .Map
    .empty[Database, js.Promise[IdbDatabaseApi]]

  override def get[A]
    (store: ObjectStore[A], keys: Seq[String])
    : IO[Map[String, A]] =
    if keys.isEmpty then IO.pure(Map.empty)
    else
      reading(store): objects =>
        val requests = keys.distinct.map(key => key -> objects.get(key))
        () =>
          requests
            .flatMap((key, request) =>
              decode(store, key, request.result).map(key -> _),
            )
            .toMap

  override def all[A](store: ObjectStore[A]): IO[SortedMap[String, A]] =
    reading(store): objects =>
      val keys   = objects.getAllKeys()
      val values = objects.getAll()
      () =>
        SortedMap.from(
          results(keys)
            .zip(results(values))
            .collect:
              case (key, json) if js.typeOf(key) == "string" =>
                key.asInstanceOf[String] -> json
            .flatMap((key, json) => decode(store, key, json).map(key -> _)),
        )

  override def write(writes: Database.Write*): IO[Unit] =
    writes.map(_.store.database).distinct match
      case Seq()         => IO.unit
      case Seq(database) => transaction(
          database,
          writes.map(_.store.name),
          WebExtensionDatabases.readwrite,
        ): transaction =>
          writes.foreach(written(transaction, _))
          () => ()
      case databases => IO.raiseError(IllegalArgumentException(
          s"Writes made at once must be of one database, as a transaction is, not of ${ databases
              .map(_.name)
              .mkString(", ") }.",
        ))

  override def update[A]
    (store: ObjectStore[A], keys: Seq[String])
    (transform: (String, Option[A]) => Option[A])
    : IO[Map[String, A]] =
    if keys.isEmpty then IO.pure(Map.empty)
    else
      transaction(
        store.database,
        List(store.name),
        WebExtensionDatabases.readwrite,
      ): transaction =>
        val objects = transaction.store(store.name)
        val updated = mutable.Map.empty[String, A]
        keys
          .distinct
          .foreach: key =>
            val request = objects.get(key)
            request.onsuccess = transaction.guarded:
              transform(key, decode(store, key, request.result)) match
                case Some(value) =>
                  objects.put(store.codec.encodeJs(value), key)
                  updated(key) = value
                case None => objects.delete(key)
        () => updated.toMap

  /**
    * What `make` gives, once the readonly transaction over `store`, in whose
    * store it makes its requests, completes.
    */
  private def reading[A, B]
    (store: ObjectStore[A])
    (make: IdbObjectStoreApi => () => B)
    : IO[B] = transaction(
    store.database,
    List(store.name),
    WebExtensionDatabases.readonly,
  )(transaction => make(transaction.store(store.name)))

  /**
    * What `make` gives, once the transaction of `mode` over the stores `names`
    * of `database`, in which it makes its requests, completes. The transaction
    * is begun and `make` run within the one callback, as the browser commits
    * the transaction once it turns to another task, and `make` gives what reads
    * the requests' results once it has. It fails if the transaction is aborted,
    * as by a failure of `make` or of a callback that it guards. Cancelled, it
    * ends at once, and the transaction carries on.
    */
  private def transaction[A]
    (
      database: Database,
      names: Seq[String],
      mode: String,
    )
    (make: Transaction => () => A)
    : IO[A] = connection(database).flatMap(connection =>
    IO.async[A](done =>
      IO(
        begin(
          database,
          connection,
          names.distinct,
          mode,
        ).run(make, done),
      ).as(Some(IO.unit)),
    ),
  )

  /** A new transaction of `mode` over the stores `names` of `connection`. */
  private def begin
    (
      database: Database,
      connection: IdbDatabaseApi,
      names: Seq[String],
      mode: String,
    )
    : Transaction =
    try
      Transaction(
        database,
        connection.transaction(js.Array(names*), mode),
      )
    catch
      case js.JavaScriptException(exception) =>
        throw WebExtensionDatabases.error(
          database,
          exception.asInstanceOf[DomExceptionApi],
        )

  /** Makes `write` in `transaction`. */
  private def written
    (
      transaction: Transaction,
      write: Database.Write,
    )
    : Unit = write match
    case Database.Put(store, key, json) =>
      transaction.store(store.name).put(convertJsonToJs(json), key)
    case Database.Delete(store, key) =>
      transaction.store(store.name).delete(key)
    case Database.Clear(store) => transaction.store(store.name).clear()

  /**
    * The connection to `database`, opened the first time that it is asked for,
    * and again once it has closed, or if it could not be opened.
    */
  private def connection(database: Database): IO[IdbDatabaseApi] = IO(
    connections.getOrElseUpdate(database, opened(database)),
  ).flatMap(opening =>
    promise(opening).onError(_ =>
      IO(
        if connections.get(database).contains(opening) then
          connections.remove(database),
      ),
    ),
  )

  /**
    * A new connection to `database`, which first makes it, or upgrades it to
    * its version, if need be. If another connection to it is open at an older
    * version, it waits until that closes, as each that this opens does once
    * asked to.
    */
  private def opened(database: Database): js.Promise[IdbDatabaseApi] =
    new js.Promise[IdbDatabaseApi]((resolve, reject) =>
      val request = factory()
        .getOrElse(
          throw DatabaseError(
            database.name,
            "The browser has no IndexedDB here.",
          ),
        )
        .open(database.name, database.version)
      request.onupgradeneeded = _ => made(database, connected(request))
      request.onsuccess =
        _ => kept(database, connected(request)).fold(reject(_), resolve(_))
      request.onerror =
        _ => reject(WebExtensionDatabases.error(database, request.error)),
    )

  /** The connection that `request` opened. */
  private def connected(request: IdbOpenRequestApi): IdbDatabaseApi = request
    .result
    .asInstanceOf[IdbDatabaseApi]

  /** Makes each store of `database` that `connection`'s lacks, on upgrading. */
  private def made
    (
      database: Database,
      connection: IdbDatabaseApi,
    )
    : Unit = missing(database, connection).foreach(connection.createObjectStore)

  /**
    * `connection`, if it has every store of `database`, which closes itself
    * once a newer version is asked for, and is forgotten once it closes, or
    * else why it is not kept.
    */
  private def kept
    (
      database: Database,
      connection: IdbDatabaseApi,
    )
    : Either[DatabaseError, IdbDatabaseApi] =
    missing(database, connection) match
      case Nil =>
        connection.onversionchange = _ =>
          connection.close()
          connections.remove(database)
        connection.onclose = _ => connections.remove(database)
        Right(connection)
      case names =>
        connection.close()
        Left(DatabaseError(
          database.name,
          if names.size == 1 then
            s"It lacks the store ${ names
                .head }, which was added without raising its version."
          else
            s"It lacks the stores ${ names.mkString(
                ", ",
              ) }, which were added without raising its version.",
        ))

  /** The names of the stores of `database` that `connection`'s lacks. */
  private def missing
    (
      database: Database,
      connection: IdbDatabaseApi,
    )
    : List[String] = database
    .stores
    .map(_.name)
    .filterNot(connection.objectStoreNames.contains)

  /**
    * The value of `store` that `json` holds as kept under `key`, if anything is
    * kept there, unless it cannot be decoded, as when an earlier version kept
    * it otherwise, of which it warns.
    */
  private def decode[A]
    (
      store: ObjectStore[A],
      key: String,
      json: js.Any,
    )
    : Option[A] =
    if js.isUndefined(json) then None
    else store.codec.decodeJs(json).left.map(warn(store, key, _)).toOption

  private def warn
    (
      store: ObjectStore[?],
      key: String,
      reason: String,
    )
    : Unit = console.warn(
    s"The value of $key in $store cannot be decoded, so it is read as none: $reason",
  )

  /** The values that `request` gave, as an array. */
  private def results(request: IdbRequestApi): List[js.Any] = request
    .result
    .asInstanceOf[js.Array[js.Any]]
    .toList

/**
  * A transaction of `database`, `api`, which keeps why it was aborted if one of
  * the callbacks that it guards failed, so that it fails with that.
  */
private final class Transaction
  (
    database: Database,
    api: IdbTransactionApi,
  ):

  /** Why it was aborted, if by a failure of its own requests' callbacks. */
  private var failure = Option.empty[Throwable]

  /** Its store named `name`. */
  def store(name: String): IdbObjectStoreApi = api.objectStore(name)

  /**
    * A callback of a request that does `action`, aborting the transaction if it
    * fails, before anything that it wrote is kept.
    */
  def guarded(action: => Unit): js.Function1[js.Any, Unit] =
    _ => attempt(action)

  /**
    * Makes the transaction's first requests through `make`, and gives `done`
    * what it gives, read once the transaction completes, or why the transaction
    * was aborted.
    */
  def run[A]
    (
      make: Transaction => () => A,
      done: Either[Throwable, A] => Unit,
    )
    : Unit =
    val read = Try(make(this))
    read.failed.foreach(abort)
    api.oncomplete = _ => done(read.flatMap(read => Try(read())).toEither)
    api.onabort = _ =>
      done(Left(
        failure.getOrElse(WebExtensionDatabases.error(database, api.error)),
      ))

  private def attempt(action: => Unit): Unit =
    try action
    catch case NonFatal(error) => abort(error)

  /** Aborts the transaction, which fails with `error`. */
  private def abort(error: Throwable): Unit =
    failure = failure.orElse(Some(error))
    Try(api.abort())

private[omnibrowser] object WebExtensionDatabases:

  val readonly  = "readonly"
  val readwrite = "readwrite"

  /** The failure of `database`, for the reason that `exception` gives. */
  def error
    (
      database: Database,
      exception: DomExceptionApi,
    )
    : DatabaseError = DatabaseError(
    database.name,
    Option(exception).fold("The browser gave no reason.")(exception =>
      s"${ exception.name }: ${ exception.message }",
    ),
  )
