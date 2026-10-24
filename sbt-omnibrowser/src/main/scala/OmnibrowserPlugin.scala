package com.alecdorrington.omnibrowser.plugin

import org.scalajs.linker.interface.{OutputPatterns, Report}
import org.scalajs.sbtplugin.ScalaJSPlugin
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.*
import sbt.{*, given}
import sbt.Keys.*
import sbt.internal.util.MessageOnlyException
import xsbt.api.Discovery
import xsbti.api.{ClassLike, Parameterized, Type}
import xsbti.compile.CompileAnalysis

/**
  * Builds a browser extension, defined by an object extending
  * `BrowserExtension`, for each target browser.
  */
object OmnibrowserPlugin extends AutoPlugin:

  override def requires: Plugins = ScalaJSPlugin

  override def trigger: PluginTrigger = noTrigger

  object autoImport:

    type Target = com.alecdorrington.omnibrowser.plugin.Target

    val Target = com.alecdorrington.omnibrowser.plugin.Target

    val omnibrowserExtension = settingKey[Option[String]](
      "The object defining the extension, if not the only one extending BrowserExtension.",
    )

    val omnibrowserTargets =
      settingKey[Seq[Target]]("The browsers to build the extension for.")

    val omnibrowserGeckoId = settingKey[String](
      "The extension's ID in Firefox, in the form of an email address.",
    )

    val omnibrowserAssets = settingKey[File](
      "The directory of files copied into the extension as they are, such as its icons.",
    )

    // Neither is cached, as each runs the bundle and writes its files.

    @transient
    val omnibrowserBuild = taskKey[Seq[File]](
      "Builds the extension for development, unpacked, for each target.",
    )

    @transient
    val omnibrowserPackage = taskKey[Seq[File]](
      "Builds the extension for release, zipped, for each target.",
    )

  import autoImport.*

  /** The class that the object defining an extension extends. */
  private val extensionClass = "com.alecdorrington.omnibrowser.BrowserExtension"

  /** The entry point of every extension's bundle. */
  private val entry = "com.alecdorrington.omnibrowser.launch.Entry"

  override lazy val projectSettings: Seq[Setting[?]] = Seq(
    omnibrowserExtension := None,
    omnibrowserTargets   := Target.values.toSeq,
    omnibrowserGeckoId := s"${ normalizedName.value }@${ organization.value }",
    omnibrowserAssets  := (Compile / sourceDirectory).value / "extension",
    scalaJSUseMainModuleInitializer := true,
    Compile / mainClass             := Some(entry),
    Compile / scalaJSLinkerConfig ~=
      (_.withOutputPatterns(OutputPatterns.fromJSFile(Assembly.bundle))),
    omnibrowserBuild   := assemble(fastLinkJS, "development").value,
    omnibrowserPackage := zip(assemble(fullLinkJS, "release")).value,
  )

  /** Lays out the extension for each target, from the bundle that `link` links. */
  private def assemble
    (
      link: TaskKey[Attributed[Report]],
      mode: String,
    )
    : Def.Initialize[Task[Seq[File]]] = Def.task {
    (Compile / link).value
    val linked    = (Compile / link / scalaJSLinkerOutputDirectory).value
    val extension = extensionName.value
    val directory = target.value / "omnibrowser" / mode
    val runner    = Runner.write(streams.value.cacheDirectory)
    val assets    = omnibrowserAssets.value
    val build     = version.value
    val geckoId   = omnibrowserGeckoId.value
    val log       = streams.value.log
    omnibrowserTargets
      .value
      .map(browser =>
        Assembly(extension, browser, build, geckoId).into(
          directory / browser.id,
          linked,
          assets,
          runner,
          log,
        ),
      )
  }

  /** Zips each layout of the extension that `layouts` makes, for release. */
  private def zip
    (layouts: Def.Initialize[Task[Seq[File]]])
    : Def.Initialize[Task[Seq[File]]] = Def.task {
    val prefix = s"${ normalizedName.value }-${ version.value }"
    layouts
      .value
      .map(directory =>
        Assembly.zip(
          directory,
          directory.getParentFile / s"$prefix-${ directory.getName }.zip",
        ),
      )
  }

  /**
    * The name of the object defining the extension: the one that the user
    * chose, or else the only one that there is.
    */
  private val extensionName: Def.Initialize[Task[String]] = Def.task {
    val discovered = discover((Compile / compile).value)
    omnibrowserExtension.value.getOrElse(only(discovered))
  }

  /**
    * The objects that extend `BrowserExtension`, which sbt's own discovery
    * misses, as it overlooks parents with type arguments.
    */
  private def discover(analysis: CompileAnalysis): Seq[String] = Tests
    .allDefs(analysis)
    .collect:
      case module: ClassLike if isExtension(module) => module.name

  private def isExtension(module: ClassLike): Boolean =
    Discovery.isModule(module) &&
    module.structure.parents.flatMap(className).contains(extensionClass)

  private def className(parent: Type): Option[String] = parent match
    case parameterized: Parameterized => className(parameterized.baseType)
    case other                        => Discovery.simpleName(other)

  private def only(names: Seq[String]): String = names match
    case Seq(name) => name
    case Seq()     =>
      throw MessageOnlyException("No object extends BrowserExtension.")
    case _ => throw MessageOnlyException(
        s"Several objects extend BrowserExtension: ${ names.mkString(
            ", ",
          ) }. " + "Choose one with `omnibrowserExtension := Some(...)`.",
      )
