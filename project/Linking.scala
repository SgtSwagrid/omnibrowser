import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.*
import sbt.*

/** How the build links its Scala.js projects. */
object Linking:

  /**
    * Links in batch mode on CI, where each bundle is linked once, so that each
    * project's linker keeps none of the state that would link it again faster.
    * With every project's linker keeping its own, CI's sbt, whose heap is 1 GB,
    * ran out of heap, and its test step hung.
    */
  lazy val batchOnCi: Seq[Setting[?]] = Seq(Compile, Test).map(config =>
    config / scalaJSLinkerConfig ~= (_.withBatchMode(sys.env.contains("CI"))),
  )
