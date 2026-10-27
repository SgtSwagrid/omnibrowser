package com.alecdorrington.omnibrowser.testkit

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import scala.concurrent.ExecutionContext

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
  private var queued   = 0
  private var events   = 0
  private var failures = Vector.empty[Throwable]

  /**
    * The runtime of the extension's programs, which counts each step that it
    * has yet to run, so that the extension is busy until it has run them, as
    * between a program's start and its first message.
    */
  private val runtime: IORuntime =
    val compute = Counted(IORuntime.global.compute, this)
    IORuntime
      .builder()
      .setCompute(compute, () => ())
      .setBlocking(compute, () => ())
      .build()

  /** Starts `program` without waiting, recording any failure. */
  def launch(program: IO[Unit]): Unit =
    touch()
    program
      .handleErrorWith(error => IO(fail(error)))
      .guarantee(IO(touch()))
      .unsafeRunAndForget()(using runtime)

  /** Records that a message awaits its response. */
  def await(): Unit =
    pending += 1
    touch()

  /** Records that a message has had its response. */
  def answer(): Unit =
    pending -= 1
    touch()

  /** Records that a step of the extension's programs is waiting to run. */
  def queue(): Unit =
    queued += 1
    touch()

  /** Records that a step of the extension's programs has run. */
  def ran(): Unit =
    queued -= 1
    touch()

  /** Records that the extension did something. */
  def touch(): Unit = events += 1

  /** Records that the extension failed with `error`. */
  def fail(error: Throwable): Unit = failures :+= error

  /** What the extension is doing now. */
  def status: Status = Status(pending == 0 && queued == 0, events, failures)

  /** Forgets the failures recorded so far, once they have been reported. */
  def forgetFailures(): Unit = failures = Vector.empty

/**
  * `underlying`, which tells `activity` of each task that it has yet to run,
  * and of each that it has run.
  */
private final class Counted
  (
    underlying: ExecutionContext,
    activity: Activity,
  )
  extends ExecutionContext:

  override def execute(task: Runnable): Unit =
    activity.queue()
    underlying.execute(() =>
      try task.run()
      finally activity.ran(),
    )

  override def reportFailure(cause: Throwable): Unit =
    underlying.reportFailure(cause)
