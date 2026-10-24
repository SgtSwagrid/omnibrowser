// SBT plugins and sources particular to this project.

// For compiling the library, and the extensions built with it, to JavaScript.
// See 'https://www.scala-js.org'.
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.22.0")

// The build uses its own plugin to build the example extension, so the plugin's
// sources are compiled into the build definition too.
Compile / unmanagedSourceDirectories +=
  baseDirectory.value.getParentFile / "sbt-omnibrowser" / "src" / "main" /
    "scala"
