import com.alecdorrington.omnibrowser.plugin.OmnibrowserPlugin
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

// The tests give the fake browser's pages a DOM through jsdom, from npm.
Npm.installation

// Scala.js links every project's bundles at once unless told otherwise, which
// adds to what CI's sbt must hold in its heap at once.
Global / concurrentRestrictions += Tags.limit(ScalaJSTags.Link, 2)

/** The library itself: the model of the browser, and its implementation. */
lazy val `omnibrowser-core` = project
  .in(file("core"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    Linking.batchOnCi,
    packagePrefix := projectRoot,
    // Its entry points start the bundles of an extension, not a program.
    Compile / mainClass := None,
    Dependencies.catsEffect,
    Dependencies.scalajsDom,
    Dependencies.munit,
  )

/** An in-memory fake browser, for testing extensions written with the library. */
lazy val `omnibrowser-testkit` = project
  .in(file("testkit"))
  .dependsOn(`omnibrowser-core`)
  .enablePlugins(ScalaJSPlugin)
  .settings(
    Linking.batchOnCi,
    packagePrefix := s"$projectRoot.testkit",
    Dependencies.munitCatsEffect,
    Npm.beforeTests,
  )

/** Pages and content scripts whose content Laminar renders. */
lazy val `omnibrowser-laminar` = project
  .in(file("laminar"))
  .dependsOn(
    `omnibrowser-core`,
    `omnibrowser-testkit` % Test,
  )
  .enablePlugins(ScalaJSPlugin)
  .settings(
    Linking.batchOnCi,
    packagePrefix := s"$projectRoot.laminar",
    Dependencies.laminar,
    Dependencies.munitCatsEffect,
    Npm.beforeTests,
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
  .dependsOn(
    `omnibrowser-core`,
    `omnibrowser-laminar`,
    `omnibrowser-testkit` % Test,
  )
  .enablePlugins(OmnibrowserPlugin)
  .settings(
    Linking.batchOnCi,
    packagePrefix  := s"$projectRoot.example",
    publish / skip := true,
    Dependencies.munitCatsEffect,
    Npm.beforeTests,
  )

/**
  * An extension whose content scripts share a value in each way that a page's
  * may, which its tests run as the plugin built it too, where each content
  * script loads its own copy of the content bundle.
  */
lazy val `omnibrowser-sharing` = project
  .in(file("sharing"))
  .dependsOn(
    `omnibrowser-core`,
    `omnibrowser-testkit` % Test,
  )
  .enablePlugins(OmnibrowserPlugin)
  .settings(
    Linking.batchOnCi,
    packagePrefix  := s"$projectRoot.sharing",
    publish / skip := true,
    Dependencies.munitCatsEffect,
    Npm.beforeTests,
  )

lazy val omnibrowser = project
  .in(file("."))
  .enablePlugins(ScalaUnidocPlugin)
  .aggregate(
    `omnibrowser-core`,
    `omnibrowser-testkit`,
    `omnibrowser-laminar`,
    `sbt-omnibrowser`,
    `omnibrowser-example`,
    `omnibrowser-sharing`,
  )
  .settings(
    publish / skip                             := true,
    ScalaUnidoc / unidoc / unidocProjectFilter := inProjects(
      `omnibrowser-core`,
      `omnibrowser-testkit`,
      `omnibrowser-laminar`,
    ),
    ScalaUnidoc / unidoc / scalacOptions ++= Seq("-project", "Omnibrowser"),
  )
