package com.alecdorrington.omnibrowser.testkit

import cats.effect.IO
import cats.effect.unsafe.implicits.global

/** A moment's account of what an extension is doing in a [[FakeBrowser]]. */
private[testkit] final case class Status
  (
    idle: Boolean,
    events: Int,
    failures: Vector[Throwable],
  )

/**
  * What an extension is doing in a [[FakeBrowser]], so that a test can wait
  * until it has finished. It is mutable, as the browser's callbacks report to
  * it as they happen.
  */
private[testkit] final class Activity:

  private var pending  = 0
  private var events   = 0
  private var failures = Vector.empty[Throwable]

  /** Starts `program` without waiting, recording any failure. */
  def launch(program: IO[Unit]): Unit =
    touch()
    program
      .handleErrorWith(error => IO(fail(error)))
      .guarantee(IO(touch()))
      .unsafeRunAndForget()

  /** Records that a message awaits its response. */
  def await(): Unit =
    pending += 1
    touch()

  /** Records that a message has had its response. */
  def answer(): Unit =
    pending -= 1
    touch()

  /** Records that the extension did something. */
  def touch(): Unit = events += 1

  /** Records that the extension failed with `error`. */
  def fail(error: Throwable): Unit = failures :+= error

  /** What the extension is doing now. */
  def status: Status = Status(pending == 0, events, failures)

  /** Forgets the failures recorded so far, once they have been reported. */
  def forgetFailures(): Unit = failures = Vector.empty
