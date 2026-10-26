package com.alecdorrington.omnibrowser.webext

import cats.effect.{IO, Resource}
import cats.effect.std.{Dispatcher, Queue}
import fs2.Stream
import scala.scalajs.js

/** Conversions from the WebExtensions API's promises, events and ports. */
private[omnibrowser] object Interop:

  /** The result of the promise that `start` makes. */
  def promise[A](start: => js.Promise[A]): IO[A] = IO.fromPromise(IO(start))

  /**
    * The values that `event` gives a listener while the stream runs, where
    * `listener` makes the listener from a function that it gives each value.
    */
  def events[A, L <: js.Function]
    (event: Event[L])
    (listener: (A => Unit) => L)
    : Stream[IO, A] =
    for
      offer <- offering[A]
      value <- Stream.resource(listening(event, listener(offer))) >>
        Stream.fromQueueUnterminated(offer.queue)
    yield value

  /**
    * The messages that the port `open` opens is sent, until it is disconnected,
    * which it is once the stream ends. It is opened along with its listeners,
    * so that they hear of everything that it is sent.
    */
  def port(open: => PortApi): Stream[IO, js.Any] =
    for
      offer <- offering[Option[js.Any]]
      _     <-
        Stream.bracket(IO(attach(open, offer)))(port => IO(port.disconnect()))
      message <- Stream.fromQueueNoneTerminated(offer.queue)
    yield message

  /** Adds `listener` to `event` while the resource is in use. */
  def listening[L <: js.Function]
    (event: Event[L], listener: L)
    : Resource[IO, Unit] = Resource.make(IO(event.addListener(listener)))(_ =>
    IO(event.removeListener(listener)),
  )

  /** Gives `offer` each message that `port` is sent, then nothing once it ends. */
  private def attach
    (
      port: PortApi,
      offer: Option[js.Any] => Unit,
    )
    : PortApi =
    port.onMessage.addListener(message => offer(Some(message)))
    port.onDisconnect.addListener(_ => offer(None))
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
