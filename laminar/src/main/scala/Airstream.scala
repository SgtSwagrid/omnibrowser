package com.alecdorrington.omnibrowser.laminar

import cats.effect.{Deferred, IO}
import com.alecdorrington.omnibrowser.{ContentBrowser, Granted, StorageKey}
import com.alecdorrington.omnibrowser.Permission.Storage
import com.raquo.laminar.api.L.{EventStream, Observer, Signal}
import fs2.Stream

extension (browser: ContentBrowser)

  /**
    * Each value of `values`, which the browser runs while anything observes the
    * event stream, from its beginning each time that the event stream starts.
    * Its failure is an error of the event stream.
    */
  def events[A](values: Stream[IO, A]): EventStream[A] =
    val source = StreamSource(values, browser)
    EventStream.fromCustomSource[A](
      start = (emit, fail, _, _) => source.start(emit, fail),
      stop = _ => source.stop(),
    )

  /**
    * The result of `program`, which the browser runs each time that the event
    * stream starts.
    */
  def events[A](program: IO[A]): EventStream[A] = events(Stream.eval(program))

  /**
    * The value of `key`, which is its default until it has been read, then each
    * new value that it is given, while anything observes the signal.
    */
  def signal[A](key: StorageKey[A])(using Granted[Storage]): Signal[A] =
    events(browser.storage.values(key)).toSignal(key.default)

  /** Gives `key` each value that the observer observes. */
  def writer[A](key: StorageKey[A])(using Granted[Storage]): Observer[A] =
    observer(browser.storage.set(key, _))

  /**
    * Transforms the value of `key` by `transform` each time that the observer
    * observes anything. An update that another part of the extension makes
    * meanwhile may be lost.
    */
  def updater[A]
    (key: StorageKey[A])
    (transform: A => A)
    (using Granted[Storage])
    : Observer[Any] = observer(_ => browser.storage.update(key)(transform).void)

  /**
    * Runs `program` with each value that the observer observes, at once, as
    * from the handler of a DOM event, through [[ContentBrowser.run]], so that
    * an API that needs the user's input still sees it.
    */
  def observer[A](program: A => IO[Unit]): Observer[A] =
    Observer(value => browser.run(program(value)))

/**
  * The source of an event stream of the values of `values`, which `browser`
  * runs each time that the event stream starts, until it stops. It is mutable,
  * as Airstream starts and stops it.
  */
private final class StreamSource[A]
  (
    values: Stream[IO, A],
    browser: ContentBrowser,
  ):

  /** Completed once the event stream stops, while it is started. */
  private var stopped = Option.empty[Deferred[IO, Unit]]

  /**
    * Runs `values` until the event stream stops, giving `emit` each value, and
    * `fail` any failure.
    */
  def start(emit: A => Unit, fail: Throwable => Unit): Unit =
    val stop = Deferred.unsafe[IO, Unit]
    stopped = Some(stop)
    browser.run(
      values
        .interruptWhen(stop.get.attempt)
        .foreach(value => IO(if isRunning(stop) then emit(value)))
        .compile
        .drain
        .handleError(error => if isRunning(stop) then fail(error)),
    )

  /** Stops running `values`. */
  def stop(): Unit =
    stopped.foreach(stop => browser.run(stop.complete(()).void))
    stopped = None

  /**
    * Whether the run that `stop` stops is still running, rather than stopped,
    * so that nothing that it gives after it stops reaches the event stream.
    */
  private def isRunning(stop: Deferred[IO, Unit]): Boolean =
    stopped.contains(stop)
