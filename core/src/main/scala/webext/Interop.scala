package com.alecdorrington.omnibrowser.webext

import cats.effect.{IO, Resource}
import cats.effect.std.{Dispatcher, Queue}
import fs2.Stream
import scala.scalajs.js

/** Conversions from the WebExtensions API's promises, events and ports. */
private[omnibrowser] object Interop:

  /**
    * The result of the promise that `start` makes. The promise is handled at
    * once, as a program that runs at once until its first asynchronous step
    * only waits for it afterwards, so that its failure is not reported as
    * unhandled meanwhile. The program still fails with it.
    */
  def promise[A](start: => js.Promise[A]): IO[A] =
    IO.fromPromise(IO(handled(start)))

  /** `promise`, once it is handled, as by a listener that ignores failure. */
  private def handled[A](promise: js.Promise[A]): js.Promise[A] =
    promise.asInstanceOf[js.Dynamic].applyDynamic("catch")(ignore)
    promise

  /**
    * The values that `event` gives a listener while the stream runs, where
    * `listener` makes the listener from a function that it gives each value.
    */
  def events[A, L <: js.Function]
    (event: Event[L])
    (listener: (A => Unit) => L)
    : Stream[IO, A] = heard(event, listener)(Stream.fromQueueUnterminated(_))

  /**
    * `first`, once the stream listens to `event`, then the values that `event`
    * gives the listener, as [[events]] does.
    */
  def eventsFrom[A, L <: js.Function]
    (first: A)
    (event: Event[L])
    (listener: (A => Unit) => L)
    : Stream[IO, A] = heard(event, listener)(queue =>
    Stream.emit(first) ++ Stream.fromQueueUnterminated(queue),
  )

  /**
    * The messages that the port `open` opens is sent, until it is disconnected,
    * which it is once the stream ends. It is opened along with its listeners,
    * so that they hear of everything that it is sent. Its failure to connect is
    * read from `runtime`, as Chrome reports it as an error otherwise.
    */
  def port(open: => PortApi, runtime: RuntimeApi): Stream[IO, js.Any] =
    for
      offer <- offering[Option[js.Any]]
      _     <- Stream.bracket(IO(attach(open, runtime, offer)))(port =>
        IO(port.disconnect()),
      )
      message <- Stream.fromQueueNoneTerminated(offer.queue)
    yield message

  /** A listener that ignores what it hears. */
  private val ignore: js.Function1[js.Any, Unit] = _ => ()

  /** Adds `listener` to `event` while the resource is in use. */
  def listening[L <: js.Function]
    (event: Event[L], listener: L)
    : Resource[IO, Unit] = Resource.make(IO(event.addListener(listener)))(_ =>
    IO(event.removeListener(listener)),
  )

  /**
    * What `values` makes of the queue of the values that `event` gives a
    * listener, once the stream listens.
    */
  private def heard[A, B, L <: js.Function]
    (event: Event[L], listener: (A => Unit) => L)
    (values: Queue[IO, A] => Stream[IO, B])
    : Stream[IO, B] =
    for
      offer <- offering[A]
      value <- Stream.resource(listening(event, listener(offer))) >>
        values(offer.queue)
    yield value

  /**
    * Gives `offer` each message that `port` is sent, then nothing once it ends,
    * having read why from `runtime`.
    */
  private def attach
    (
      port: PortApi,
      runtime: RuntimeApi,
      offer: Option[js.Any] => Unit,
    )
    : PortApi =
    port.onMessage.addListener(message => offer(Some(message)))
    port
      .onDisconnect
      .addListener(_ =>
        runtime.lastError.foreach(_ => ())
        offer(None),
      )
    port

  /** A function that offers each value it is given to its queue, at once. */
  private def offering[A]: Stream[IO, Offer[A]] =
    for
      dispatcher <- Stream.resource(Dispatcher.sequential[IO])
      queue      <- Stream.eval(Queue.unbounded[IO, A])
    yield Offer(queue, dispatcher)

/** Offers each value that it is given to `queue`, through `dispatcher`. */
private final class Offer[A]
  (
    val queue: Queue[IO, A],
    dispatcher: Dispatcher[IO],
  )
  extends (A => Unit):

  override def apply(value: A): Unit =
    dispatcher.unsafeRunAndForget(queue.offer(value))
