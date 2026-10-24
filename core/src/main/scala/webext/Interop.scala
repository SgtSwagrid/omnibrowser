package com.alecdorrington.omnibrowser.webext

import cats.effect.IO
import cats.effect.std.{Dispatcher, Queue}
import fs2.Stream
import scala.scalajs.js

/** Conversions from the WebExtensions API's promises and events. */
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
      dispatcher <- Stream.resource(Dispatcher.sequential[IO])
      queue      <- Stream.eval(Queue.unbounded[IO, A])
      handler =
        listener(value => dispatcher.unsafeRunAndForget(queue.offer(value)))
      _ <- Stream.bracket(IO(event.addListener(handler)))(_ =>
        IO(event.removeListener(handler)),
      )
      value <- Stream.fromQueueUnterminated(queue)
    yield value
