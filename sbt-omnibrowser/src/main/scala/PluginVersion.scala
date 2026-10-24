package com.alecdorrington.omnibrowser.plugin

import java.io.File
import java.util.jar.{Attributes, JarFile}
import scala.util.{Try, Using}

/** The version of the plugin, as its jar records it. */
private[plugin] object PluginVersion:

  /** The title that the plugin's own jar records, as its project's name. */
  private val title = "sbt-omnibrowser"

  /**
    * The version of the published plugin, or none when the plugin is compiled
    * from its sources into a build definition, as in its own build, whose jar
    * records the build definition's version instead.
    */
  lazy val current: Option[String] = jar
    .flatMap(attributes)
    .collect:
      case found
        if found.getValue(Attributes.Name.IMPLEMENTATION_TITLE) == title =>
        found.getValue(Attributes.Name.IMPLEMENTATION_VERSION)

  /** The jar that holds this class, if it is in one. */
  private def jar: Option[File] = Try(
    File(getClass.getProtectionDomain.getCodeSource.getLocation.toURI),
  ).toOption.filter(_.getName.endsWith(".jar"))

  private def attributes(jar: File): Option[Attributes] = Using(
    JarFile(jar),
  )(file => Option(file.getManifest).map(_.getMainAttributes)).toOption.flatten
