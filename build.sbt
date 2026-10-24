import com.alecdorrington.omnibrowser.plugin.OmnibrowserPlugin
import com.alecdorrington.omnibrowser.plugin.OmnibrowserPlugin.autoImport.*
import IdeSettings.packagePrefix
import org.scalajs.sbtplugin.ScalaJSPlugin
import sbt._
import sbt.Keys._
import sbtunidoc.BaseUnidocPlugin.autoImport.*
import sbtunidoc.ScalaUnidocPlugin

ThisBuild / scalaVersion := "3.9.0"

ThisBuild / scalacOptions ++= Seq(
  "-explain",
  "-explain-types",
  "-explain-cyclic",
)

val projectRoot = "com.alecdorrington.omnibrowser"

/** The library itself: the model of the browser, and its implementation. */
lazy val `omnibrowser-core` = project
  .in(file("core"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    packagePrefix := projectRoot,
    Dependencies.catsEffect,
    Dependencies.scalajsDom,
    Dependencies.munit,
  )

/** The sbt plugin that builds extensions written with the library. */
lazy val `sbt-omnibrowser` = project
  .in(file("sbt-omnibrowser"))
  .enablePlugins(SbtPlugin)
  .settings(
    packagePrefix := s"$projectRoot.plugin",

    // The Scala version that sbt itself runs on, which loads the plugin.
    scalaVersion := "3.8.4",
    Dependencies.scalajsPlugin,
  )

/** An extension built with the library and the plugin, as a test of both. */
lazy val `omnibrowser-example` = project
  .in(file("example"))
  .dependsOn(`omnibrowser-core`)
  .enablePlugins(OmnibrowserPlugin)
  .settings(
    packagePrefix  := s"$projectRoot.example",
    publish / skip := true,
    Dependencies.munit,

    // The tests check the extension as the plugin builds it.
    Test / testFull :=
      Def.uncached((Test / testFull).dependsOn(omnibrowserBuild).value),
  )

lazy val omnibrowser = project
  .in(file("."))
  .enablePlugins(ScalaUnidocPlugin)
  .aggregate(`omnibrowser-core`, `sbt-omnibrowser`, `omnibrowser-example`)
  .settings(
    publish / skip := true,
    ScalaUnidoc / unidoc / unidocProjectFilter :=
      inProjects(`omnibrowser-core`),
    ScalaUnidoc / unidoc / scalacOptions ++= Seq("-project", "Omnibrowser"),
  )
