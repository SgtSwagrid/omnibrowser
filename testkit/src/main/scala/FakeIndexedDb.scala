package com.alecdorrington.omnibrowser.testkit

import scala.collection.immutable.SortedMap
import scala.collection.mutable
import scala.scalajs.js
import scala.util.{Failure, Success, Try}

/** The values of a database's object stores, by their keys, by store. */
private[testkit] type Values = Map[String, SortedMap[String, js.Any]]

/**
  * What a request does to the values that its transaction sees, and what it
  * gives.
  */
private[testkit] type Operation = Values => (Values, js.Any)

/**
  * The extension's databases in a [[FakeBrowser]], which its background, its
  * pages and the tests share, kept in memory, as IndexedDB keeps them, which
  * [[FakeIndexedDb]] gives each of them. Each step is taken a moment later
  * through `later`, and each value is copied through `copy`, as the browser
  * clones it. A failure of the extension's callbacks is given to `fail`. It is
  * mutable, as the API that it fakes is.
  */
private[testkit] final class Databases
  (
    val later: (=> Unit) => Unit,
    val fail: Throwable => Unit,
    val copy: js.Any => js.Any,
  ):

  private val kept = mutable.Map.empty[String, Kept]

  /**
    * Whether the browser refuses each write, failing its transaction, as when
    * it has no room for it.
    */
  var refusing = false

  /** The browser's IndexedDB, as the background and pages see it. */
  val factory: FakeIndexedDb = FakeIndexedDb(this)

  /**
    * Opens a connection to the database `name` at `version`, or at its own if
    * none is given, through `request`, a moment later.
    */
  def open
    (
      name: String,
      version: Option[Int],
      request: FakeOpenRequest,
    )
    : Unit = later:
    val database = kept.getOrElseUpdate(name, Kept(name, this))
    connect(
      database,
      version.getOrElse(database.version max 1),
      request,
    )

  /**
    * Gives `request` a connection to `database` at `version`, first upgrading
    * it if `version` is above its own, once no connection is open at an older
    * version, or fails it if `version` is below its own.
    */
  private def connect
    (
      database: Kept,
      version: Int,
      request: FakeOpenRequest,
    )
    : Unit =
    if version < database.version then
      request.fail(
        Databases.exception(
          "VersionError",
          s"The requested version ($version) is less than the existing version (${ database
              .version }).",
        ),
        this,
      )
    else if version == database.version then
      request.succeed(database.connect(version), this)
    else
      database.askToClose(version)
      if database.isOpen then
        request.block(database.version, version, this)
        database.whenClosed(connect(database, version, request))
      else database.upgrade(version, request)

  /**
    * Calls `handler`, if it is a function, with `event`, and gives whether it
    * did not fail, as the extension's failure.
    */
  def dispatch(handler: js.Any, event: js.Any): Boolean =
    if js.typeOf(handler) != "function" then true
    else
      Try(handler.asInstanceOf[js.Function1[js.Any, Any]](event)) match
        case Success(_)     => true
        case Failure(error) =>
          fail(error)
          false

private[testkit] object Databases:

  /** A `DOMException` named `name`, as the browser throws or reports. */
  def exception(name: String, message: String): js.Any = js
    .Dynamic
    .newInstance(js.Dynamic.global.DOMException)(message, name)

  /** Throws the `DOMException` named `name`, as the browser does. */
  def raise(name: String, message: String): Nothing =
    throw js.JavaScriptException(exception(name, message))

  /** An event of `kind` at `target`, with `fields` too. */
  def event
    (
      kind: String,
      target: js.Any,
      fields: js.Object = js.Object(),
    )
    : js.Object = js
    .Object
    .assign(
      js.Dynamic.literal(`type` = kind, target = target),
      fields,
    )

  /** The fields of an event of a change from version `from` to `to`. */
  def versions(from: Int, to: Int): js.Object = js
    .Dynamic
    .literal(oldVersion = from, newVersion = to)

  /** Runs `action` once the task running it, and its microtasks, are over. */
  def afterTask(action: () => Unit): Unit = js
    .Dynamic
    .global
    .setImmediate(action: js.Function0[Unit])

/**
  * A database as the fake browser keeps it, named `name`, in `databases`: its
  * version, its stores' values, its open connections, and its transactions,
  * which run one at a time, in the order begun, each seeing all that those
  * before it wrote. It is mutable, as the database is.
  */
private[testkit] final class Kept(val name: String, databases: Databases):

  var version = 0

  var values: Values = Map.empty

  private val connections = mutable.ArrayBuffer.empty[FakeDatabase]

  private val transactions = mutable.Queue.empty[FakeTransaction]

  /** What to do once no connection is open, as an upgrade waits for. */
  private val waiting = mutable.Queue.empty[() => Unit]

  /** Whether any connection to it is open. */
  def isOpen: Boolean = connections.nonEmpty

  /** A new connection to it, at `version`. */
  def connect(version: Int): FakeDatabase =
    val connection = FakeDatabase(this, version, databases)
    connections += connection
    connection

  /**
    * Asks each open connection to close, telling it of the newer `version`
    * asked for.
    */
  def askToClose(version: Int): Unit = connections
    .toList
    .foreach(_.askToClose(this.version, version))

  /** Does `action` once no connection is open. */
  def whenClosed(action: => Unit): Unit = waiting.enqueue(() => action)

  /**
    * Opens a connection at `version` through `request`, upgrading the database
    * to it in a transaction of its own, which runs once those begun before it
    * end.
    */
  def upgrade(version: Int, request: FakeOpenRequest): Unit =
    val connection = connect(version)
    begin(connection.upgrade(this.version, version, request))

  /** Forgets `connection`, which has closed, and does what awaited that. */
  def closed(connection: FakeDatabase): Unit =
    connections -= connection
    if connections.isEmpty then
      val ready = waiting.toList
      waiting.clear()
      ready.foreach(_())

  /** Begins `transaction`, which runs once those begun before it end. */
  def begin(transaction: FakeTransaction): FakeTransaction =
    transactions.enqueue(transaction)
    if transactions.size == 1 then transaction.start()
    transaction

  /** Forgets `transaction`, which has ended, and starts the next. */
  def ended(transaction: FakeTransaction): Unit =
    transactions -= transaction
    transactions.headOption.filterNot(_.isStarted).foreach(_.start())

/** The browser's `indexedDB`, through which the extension opens its databases. */
private[testkit] final class FakeIndexedDb
  (databases: Databases)
  extends js.Object:

  def open
    (
      name: String,
      version: js.UndefOr[Int] = js.undefined,
    )
    : FakeOpenRequest =
    if version.exists(_ < 1) then
      throw js.JavaScriptException(
        js.TypeError("The version provided must not be 0."),
      )
    val request = FakeOpenRequest()
    databases.open(name, version.toOption, request)
    request

/**
  * A request of IndexedDB, which gives its `result` once it succeeds, or its
  * `error` once it fails, calling the handler of each.
  */
private[testkit] class FakeRequest extends js.Object:

  var result: js.Any = js.undefined

  var error: js.Any = null

  var readyState = "pending"

  var onsuccess: js.Any = null

  var onerror: js.Any = null

  /**
    * Gives the request `result`, and gives whether its handler did not fail,
    * through `databases`.
    */
  private[testkit] final def succeed
    (result: js.Any, databases: Databases)
    : Boolean =
    this.result = result
    readyState = "done"
    databases.dispatch(
      onsuccess,
      Databases.event("success", this),
    )

  /** Fails the request with `error`, through `databases`. */
  private[testkit] final def fail(error: js.Any, databases: Databases): Unit =
    this.error = error
    readyState = "done"
    databases.dispatch(onerror, Databases.event("error", this))

/** A request to open a database, which may first upgrade it, or wait. */
private[testkit] final class FakeOpenRequest extends FakeRequest:

  var onupgradeneeded: js.Any = null

  var onblocked: js.Any = null

  /** The transaction upgrading the database, while it does. */
  var transaction: js.Any = null

  /**
    * Tells the request that it waits for older connections to close, before it
    * upgrades the database from version `from` to `to`.
    */
  private[testkit] final def block
    (from: Int, to: Int, databases: Databases)
    : Unit = databases.dispatch(
    onblocked,
    Databases.event(
      "blocked",
      this,
      Databases.versions(from, to),
    ),
  )

  /** Tells the request that the database is upgrading from `from` to `to`. */
  private[testkit] final def upgrading
    (from: Int, to: Int, databases: Databases)
    : Boolean = databases.dispatch(
    onupgradeneeded,
    Databases.event(
      "upgradeneeded",
      this,
      Databases.versions(from, to),
    ),
  )

/**
  * A connection to `database`, at `version`, through which the extension begins
  * transactions until it closes.
  */
private[testkit] final class FakeDatabase
  (
    database: Kept,
    val version: Int,
    databases: Databases,
  )
  extends js.Object:

  val name: String = database.name

  var onversionchange: js.Any = null

  var onclose: js.Any = null

  private var closed = false

  /** The transaction upgrading the database, while it does. */
  private var upgrading = Option.empty[FakeTransaction]

  def objectStoreNames: FakeStringList =
    FakeStringList(upgrading.fold(database.values)(_.values).keys.toList.sorted)

  /** Makes a store, as only an upgrade's transaction may, while it is active. */
  def createObjectStore(name: String): js.Object =
    upgrading.filter(_.isActive) match
      case None => Databases.raise(
          "InvalidStateError",
          "The database is not running a version change transaction.",
        )
      case Some(transaction) =>
        transaction.make(name)
        js.Dynamic.literal(name = name)

  def transaction(stores: js.Any, mode: String = "readonly"): FakeTransaction =
    val names = FakeDatabase.names(stores)
    val known = database.values.keySet
    if closed || upgrading.nonEmpty then
      Databases.raise(
        "InvalidStateError",
        "The connection is closing.",
      )
    else if names.isEmpty then
      Databases.raise(
        "InvalidAccessError",
        "No stores were named.",
      )
    else if !names.forall(known) then
      Databases.raise(
        "NotFoundError",
        "One of the specified object stores was not found.",
      )
    else if mode != "readonly" && mode != "readwrite" then
      throw js.JavaScriptException(js.TypeError(s"$mode is not a mode."))
    else database.begin(FakeTransaction(database, this, names, mode, databases))

  def close(): Unit = if !closed then
    closed = true
    database.closed(this)

  /**
    * Tells the connection that a newer version, `to`, than `from` is asked for,
    * so that it may close.
    */
  private[testkit] final def askToClose(from: Int, to: Int): Unit = databases
    .dispatch(
      onversionchange,
      Databases.event(
        "versionchange",
        this,
        Databases.versions(from, to),
      ),
    )

  /**
    * The transaction upgrading the database from version `from` to this
    * connection's, which `request` asked for, and gives the connection once it
    * completes, or fails if it is aborted.
    */
  private[testkit] final def upgrade
    (
      from: Int,
      to: Int,
      request: FakeOpenRequest,
    )
    : FakeTransaction =
    request.result = this
    val transaction = FakeTransaction(
      database,
      this,
      Nil,
      "versionchange",
      databases,
      Lifecycle(
        start = () => request.upgrading(from, to, databases),
        commit = () =>
          upgrading = None
          database.version = to
          request.transaction = null
          request.succeed(this, databases)
        ,
        abort = () =>
          upgrading = None
          close()
          request.result = js.undefined
          request.transaction = null
          request.fail(
            Databases.exception(
              "AbortError",
              "The upgrade was aborted.",
            ),
            databases,
          ),
      ),
    )
    upgrading = Some(transaction)
    request.transaction = transaction
    transaction

private[testkit] object FakeDatabase:

  /** The names of the stores that `stores` names, as one name or several. */
  def names(stores: js.Any): List[String] =
    if js.Array.isArray(stores) then
      stores.asInstanceOf[js.Array[String]].toList.distinct
    else List(stores.asInstanceOf[String])

/**
  * What a transaction does as it starts, commits and is aborted, beyond what
  * every transaction does: what an upgrade does to its request.
  */
private[testkit] final case class Lifecycle
  (
    start: () => Boolean = () => true,
    commit: () => Unit = () => (),
    abort: () => Unit = () => (),
  )

/** The phases of a transaction. */
private[testkit] enum Phase:

  /** Requests may be made in it, as in the task that began it. */
  case Active

  /** No request may be made in it, as the browser has turned to other tasks. */
  case Inactive

  /** It has committed or been aborted. */
  case Finished

/**
  * A transaction of `database`'s, through `connection`, over the stores named
  * `scope` (every one, for an upgrade), of `mode`. It runs once those begun
  * before it end, making its requests in order on the values as they were then,
  * then keeps what it wrote, or nothing if it is aborted, whole.
  *
  * It is active, so that requests may be made in it, in the task that began it,
  * and in the handlers of its requests, as the browser's is, and ends once it
  * is inactive with none pending. Each step that it takes is a moment later.
  */
private[testkit] final class FakeTransaction
  (
    database: Kept,
    val db: FakeDatabase,
    scope: List[String],
    val mode: String,
    databases: Databases,
    lifecycle: Lifecycle = Lifecycle(),
  )
  extends js.Object:

  var oncomplete: js.Any = null

  var onabort: js.Any = null

  var onerror: js.Any = null

  var error: js.Any = null

  private var phase = Phase.Active

  private var started = false

  /** Whether it has written anything, which a full browser refuses. */
  private var wrote = false

  /** The values as it sees them, once it has started. */
  private var seen: Values = Map.empty

  private val pending = mutable.Queue.empty[(FakeRequest, Operation)]

  Databases.afterTask(() => deactivate())

  def objectStore(name: String): FakeObjectStore =
    if phase == Phase.Finished then
      Databases.raise(
        "InvalidStateError",
        "The transaction has finished.",
      )
    else if !(scope.contains(name) ||
      mode == "versionchange" && seen.contains(name))
    then
      Databases.raise(
        "NotFoundError",
        "The specified object store was not found.",
      )
    else FakeObjectStore(this, name)

  def abort(): Unit =
    if phase == Phase.Finished then
      Databases.raise(
        "InvalidStateError",
        "The transaction has finished.",
      )
    else end(null)

  /** The values as it sees them. */
  private[testkit] final def values: Values = seen

  /** Whether it is active, so that requests may be made in it. */
  private[testkit] final def isActive: Boolean = phase == Phase.Active

  /** Whether it has started, once those begun before it ended. */
  private[testkit] final def isStarted: Boolean = started

  /** `value`, copied as the browser clones it. */
  private[testkit] final def copy(value: js.Any): js.Any = databases.copy(value)

  /** Makes the store `name`, which only an upgrade does. */
  private[testkit] final def make(name: String): Unit =
    if seen.contains(name) then
      Databases.raise(
        "ConstraintError",
        s"The store $name exists already.",
      )
    else seen += name -> SortedMap.empty

  /**
    * A request doing `operation` once those before it are done, which `writes`
    * or not, made in it while it is active.
    */
  private[testkit] final def request
    (operation: Operation, writes: Boolean)
    : FakeRequest =
    if phase != Phase.Active then
      Databases.raise(
        "TransactionInactiveError",
        "A request was placed against a transaction which is currently not active, or which is finished.",
      )
    else if writes && mode == "readonly" then
      Databases.raise(
        "ReadOnlyError",
        "The transaction is read-only.",
      )
    else
      wrote ||= writes
      val request = FakeRequest()
      pending.enqueue(request -> operation)
      request

  /**
    * Starts it, on the values as those before it left them. An upgrade first
    * tells its request, while it is active, so that the request's handler may
    * make stores.
    */
  private[testkit] final def start(): Unit =
    started = true
    seen = database.values
    if mode != "versionchange" || activeWhile(lifecycle.start()) then
      databases.later(step())
    else end(Databases.exception("AbortError", "A handler failed."))

  /**
    * Takes its next step, once the task that made its requests is over: does
    * its next request, or else, with none pending, commits.
    */
  private def step(): Unit =
    deactivate()
    if phase != Phase.Finished then
      if pending.nonEmpty then
        val (request, operation) = pending.dequeue()
        perform(request, operation)
      else commit()

  /**
    * Does `operation`, and gives `request` its result, while it is active
    * again, so that its handler may make more requests, then takes the next
    * step. A failure of the handler aborts it, as the browser's does.
    */
  private def perform
    (
      request: FakeRequest,
      operation: Operation,
    )
    : Unit =
    val (values, result) = operation(seen)
    seen = values
    if activeWhile(request.succeed(result, databases)) then
      databases.later(step())
    else end(Databases.exception("AbortError", "A handler failed."))

  /**
    * What `dispatch`, which calls a handler, gives, while it is active, and
    * until the task calling it, and its microtasks, are over.
    */
  private def activeWhile(dispatch: => Boolean): Boolean =
    phase = Phase.Active
    val succeeded = dispatch
    Databases.afterTask(() => deactivate())
    succeeded

  /**
    * Keeps what it wrote, unless the browser refuses it, then tells its
    * handler.
    */
  private def commit(): Unit =
    if wrote && databases.refusing then
      end(Databases.exception(
        "QuotaExceededError",
        "The quota has been exceeded.",
      ))
    else
      phase = Phase.Finished
      database.values = seen
      databases.dispatch(
        oncomplete,
        Databases.event("complete", this),
      )
      lifecycle.commit()
      database.ended(this)

  /**
    * Aborts it, for `error`, keeping nothing that it wrote, and failing each
    * request still pending, then tells its handler a moment later.
    */
  private def end(error: js.Any): Unit =
    phase = Phase.Finished
    this.error = error
    pending.foreach((request, _) =>
      request.fail(
        Databases.exception(
          "AbortError",
          "The transaction was aborted.",
        ),
        databases,
      ),
    )
    pending.clear()
    database.ended(this)
    databases.later:
      lifecycle.abort()
      databases.dispatch(onabort, Databases.event("abort", this))

  private def deactivate(): Unit =
    if phase == Phase.Active then phase = Phase.Inactive

/** An object store named `name`, as `transaction` reaches it. */
private[testkit] final class FakeObjectStore
  (
    transaction: FakeTransaction,
    val name: String,
  )
  extends js.Object:

  def get(key: js.Any): FakeRequest = reading(stored =>
    stored.get(FakeObjectStore.key(key)).fold(js.undefined)(copy),
  )

  def getAll(): FakeRequest =
    reading(stored => js.Array(stored.values.map(copy).toSeq*))

  def getAllKeys(): FakeRequest =
    reading(stored => js.Array(stored.keys.toSeq.map(key => key: js.Any)*))

  def put(value: js.Any, key: js.Any): FakeRequest =
    val kept = FakeObjectStore.key(key)
    val json = copy(value)
    writing(_.updated(kept, json), kept)

  def delete(key: js.Any): FakeRequest =
    val kept = FakeObjectStore.key(key)
    writing(_ - kept, js.undefined)

  def clear(): FakeRequest = writing(_ => SortedMap.empty, js.undefined)

  /** A request reading the store's values. */
  private def reading(read: SortedMap[String, js.Any] => js.Any): FakeRequest =
    transaction.request(
      values => (values, read(values(name))),
      writes = false,
    )

  /** A request writing the store's values, which gives `result`. */
  private def writing
    (
      write: SortedMap[String, js.Any] => SortedMap[String, js.Any],
      result: js.Any,
    )
    : FakeRequest = transaction.request(
    values => (values.updated(name, write(values(name))), result),
    writes = true,
  )

  private def copy(value: js.Any): js.Any = transaction.copy(value)

private[testkit] object FakeObjectStore:

  /**
    * `key`, as a string, which is the only kind of key that the library keeps,
    * so the only kind that the fake takes.
    */
  def key(key: js.Any): String =
    if js.typeOf(key) == "string" then key.asInstanceOf[String]
    else
      Databases.raise(
        "DataError",
        "The fake browser keeps only string keys.",
      )

/** The names of a database's stores, as `objectStoreNames` gives them. */
private[testkit] final class FakeStringList
  (names: List[String])
  extends js.Object:

  val length: Int = names.size

  def item(index: Int): js.Any = names
    .lift(index)
    .fold[js.Any](null)(name => name)

  def contains(name: String): Boolean = names.contains(name)
