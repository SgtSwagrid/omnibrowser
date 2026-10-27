import sbt.*
import sbt.Keys.*

/** The libraries that the build's projects depend on. */
object Dependencies:

  object V:

    val catsEffect = "3.7.1"
    val fs2        = "3.14.0"
    val scalajsDom = "2.8.1"
    val circe      = "0.14.17"
    val laminar    = "18.0.0-M5"
    val munit      = "1.3.6"

    val munitCatsEffect = "2.2.1"

    /**
      * Also given in `plugins-omnibrowser.sbt`, which cannot see this file, as
      * it configures the build definition that this file belongs to.
      */
    val scalajs = "1.22.0"

  lazy val catsEffect = libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-effect" % V.catsEffect,
    "co.fs2"        %% "fs2-core"    % V.fs2,
  )

  lazy val scalajsDom =
    libraryDependencies += "org.scala-js" %% "scalajs-dom" % V.scalajsDom

  /** Circe's codecs, and its conversions between its JSON and JavaScript's. */
  lazy val circe = libraryDependencies ++= Seq(
    "io.circe" %% "circe-core"    % V.circe,
    "io.circe" %% "circe-scalajs" % V.circe,
  )

  lazy val laminar =
    libraryDependencies += "com.raquo" %% "laminar" % V.laminar

  lazy val munit =
    libraryDependencies += "org.scalameta" %% "munit" % V.munit % Test

  lazy val munitCatsEffect = libraryDependencies +=
    "org.typelevel" %% "munit-cats-effect" % V.munitCatsEffect % Test

  lazy val scalajsPlugin =
    addSbtPlugin("org.scala-js" % "sbt-scalajs" % V.scalajs)
