package com.alecdorrington.omnibrowser.testkit

import cats.effect.IO
import com.alecdorrington.omnibrowser.BrowserExtension
import munit.CatsEffectSuite
import scala.collection.mutable
import scala.concurrent.duration.DurationInt
import scala.scalajs.js

/** Checks the fake API itself, as the browser's that it stands in for. */
class FakeApiSuite extends CatsEffectSuite:

  test("Each of an event's listeners hears it, though one before it failed."):
    val event = FakeEvent()
    val heard = mutable.Buffer.empty[String]
    event.addListener(failing)
    event.addListener((() => heard += "second"): js.Function0[Unit])
    val outcomes = event.hear()
    assertEquals(
      outcomes.map(_.isSuccess),
      Seq(false, true),
    )
    assertEquals(heard.toList, List("second"))
    intercept[IllegalStateException](event.fire())

  test(
    "A port that reaches nothing fails the test unless its listener reads why.",
  ):
    for
      (simulation, activity) <- simulated
      page = simulation.context(Kind.Page)
      tab <- IO(simulation.openTab("https://example.com/", "")._1.id)
      _   <- IO(connect(page, tab, "unread"))
      _   <-
        IO(connect(page, tab, "read").onDisconnect.addListener(reading(page)))
      _ <- IO.sleep(20.millis)
    yield
      assertEquals(
        activity.status.failures,
        Vector(UncheckedError(Simulation.unreachable)),
      )
      assertEquals(simulation.openPorts, 0)

  test(
    "A port that its own side closes before it reaches nothing is not reported.",
  ):
    for
      (simulation, activity) <- simulated
      page = simulation.context(Kind.Page)
      tab <- IO(simulation.openTab("https://example.com/", "")._1.id)
      _   <- IO(connect(page, tab, "closed").disconnect())
      _   <- IO.sleep(20.millis)
    yield
      assertEquals(activity.status.failures, Vector.empty)
      assertEquals(simulation.openPorts, 0)

  test("A port asked for without a name is named \"\"."):
    simulated.map: (simulation, _) =>
      val page = simulation.context(Kind.Page)
      assertEquals(
        page.api.tabs.get.connect(1, js.undefined).name,
        "",
      )

  test("A message that reaches no listener is rejected."):
    for
      (simulation, _) <- simulated
      _               <- IO(simulation.install())
      tester = simulation.context(Kind.Test)
      result <- IO
        .fromPromise(IO(tester.api.runtime.sendMessage("Anyone?")))
        .attempt
    yield assertEquals(
      reason(result),
      Some(Simulation.unreachable),
    )

  test("A listener's failure is the extension's, and the others still hear."):
    for
      (simulation, activity) <- simulated
      heard = mutable.Buffer.empty[String]
      _ <- IO(
        simulation.context(Kind.Page).api.storage.onChanged.addListener(failing),
      )
      _ <- IO(
        simulation
          .context(Kind.Page)
          .api
          .storage
          .onChanged
          .addListener((() => heard += "other"): js.Function0[Unit]),
      )
      _ <- IO(simulation.write("local", js.Dictionary("key" -> 1)))
    yield
      assertEquals(heard.toList, List("other"))
      assertEquals(activity.status.failures.size, 1)

  test("A listener's failure in a later event is the extension's."):
    for
      (simulation, activity) <- simulated
      _                      <- IO(
        simulation.later(throw IllegalStateException("Deliberately failed.")),
      )
      _ <- IO.sleep(20.millis)
    yield assertEquals(activity.status.failures.size, 1)

  test("A page that has gone can neither send nor store anything."):
    for
      (simulation, _) <- simulated
      _               <- IO(simulation.install())
      tab <- IO(simulation.openTab("https://example.com/", "")._1.id)
      content = simulation.context(Kind.Content(tab))
      _    <- IO(simulation.close(tab))
      sent <- IO
        .fromPromise(IO(content.api.runtime.sendMessage("Hello?")))
        .attempt
      kept <- IO
        .fromPromise(IO(
          content.api.storage.local.set(js.Dictionary("key" -> 1)),
        ))
        .attempt
    yield
      assertEquals(
        reason(sent),
        Some(Simulation.invalidated),
      )
      assertEquals(
        reason(kept),
        Some(Simulation.invalidated),
      )

  /** A fake browser into which [[Bare]] is installed, and what it is doing. */
  private def simulated: IO[(Simulation, Activity)] = IO:
    val activity = Activity()
    (
      Simulation(
        InProcess(Bare, activity.launch),
        activity,
      ),
      activity,
    )

  /** Connects a port named `name` from `context` to `tab`. */
  private def connect
    (
      context: FakeContext,
      tab: Int,
      name: String,
    )
    : FakePort = context
    .api
    .tabs
    .get
    .connect(tab, js.Dynamic.literal(name = name))

  /** A listener that reads why the last call failed, from `context`. */
  private def reading(context: FakeContext): js.Function1[js.Any, Unit] =
    _ => context.api.runtime.lastError.foreach(_ => ())

  /** A listener that fails. */
  private val failing: js.Function0[Unit] =
    () => throw IllegalStateException("Deliberately failed.")

  /** Why `result` failed, as a JavaScript error says, if it did. */
  private def reason(result: Either[Throwable, ?]): Option[String] = result
    .left
    .toOption
    .collect:
      case js.JavaScriptException(error) =>
        error.asInstanceOf[js.Dynamic].message.asInstanceOf[String]

/** An extension with no parts. */
object Bare extends BrowserExtension[Any]:

  override val name = "Bare"
