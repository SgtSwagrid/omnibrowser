package com.alecdorrington.omnibrowser.plugin

import org.scalajs.jsenv.nodejs.NodeJSEnv
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

    val omnibrowserVersion = settingKey[String](
      "The version of the plugin, and of the libraries that it adds.",
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

  /**
    * The environment variable through which the tests learn where the extension
    * is built for development, as `FakeBrowser.installBuilt` reads.
    */
  private val buildVariable = "OMNIBROWSER_BUILD"

  override lazy val projectSettings: Seq[Setting[?]] = Seq(
    omnibrowserVersion := PluginVersion.current.getOrElse(version.value),
    libraryDependencies ++= PluginVersion.current.toSeq.flatMap(libraries),
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

    // The tests may check the extension as it is built, so every way of
    // running them builds it first.
    Test / jsEnvInput :=
      Def.uncached((Test / jsEnvInput).dependsOn(omnibrowserBuild).value),
    Test / jsEnv := Def.uncached(NodeJSEnv(
      NodeJSEnv
        .Config()
        .withEnv(Map(buildVariable -> layouts("development").value.toString)),
    )),
  )

  /**
    * The libraries that an extension uses, at `version`: the library itself,
    * and the testkit for its tests.
    */
  private def libraries(version: String): Seq[ModuleID] = Seq(
    "com.alecdorrington" %% "omnibrowser-core"    % version,
    "com.alecdorrington" %% "omnibrowser-testkit" % version % Test,
  )

  /** Where the extension is laid out for `mode`, with a directory per target. */
  private def layouts(mode: String): Def.Initialize[File] =
    Def.setting(target.value / "omnibrowser" / mode)

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
    val directory = layouts(mode).value
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
    val built  = layouts.value
    built.map(_.getParentFile).distinct.foreach(Assembly.clearZips)
    built.map(directory =>
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
