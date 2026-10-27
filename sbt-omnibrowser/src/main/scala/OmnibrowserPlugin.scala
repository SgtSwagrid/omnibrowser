package com.alecdorrington.omnibrowser.plugin

import org.scalajs.jsenv.nodejs.NodeJSEnv
import org.scalajs.linker.interface.{
  ClearableLinker, ESVersion, LinkingException, ModuleInitializer,
  OutputPatterns, Report,
}
import org.scalajs.sbtplugin.{CacheBox, ScalaJSPlugin}
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.*
import sbt.{*, given}
import sbt.Keys.*
import sbt.internal.util.MessageOnlyException
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
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

  /** The ECMAScript version that every extension's bundle is linked for. */
  private val esVersion = ESVersion.ES2021

  /** The entry point of every extension's main bundle. */
  private val entry = "com.alecdorrington.omnibrowser.launch.Entry"

  /** The entry point of the bundle of every extension's content scripts. */
  private val contentEntry =
    "com.alecdorrington.omnibrowser.launch.ContentEntry"

  /**
    * The linker of the content scripts' bundle at each stage, kept from one
    * build to the next so that each links again only what changed.
    */
  private val contentLinker = settingKey[CacheBox[ClearableLinker]](
    "The linker of the content scripts' bundle.",
  )

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

    // A release is linked without a source map, which only developers use, and
    // which names the sources by their paths on the machine that built it, so
    // that the same source built elsewhere would differ: stores such as Firefox
    // Add-ons rebuild each version from its source to compare.
    Compile / fullLinkJS / scalaJSLinkerConfig ~= (_.withSourceMap(false)),

    // Every browser that runs Manifest V3 runs ES2021, which Scala.js needs
    // for the whole of java.util.regex, such as Unicode classes like \p{L}.
    Compile / scalaJSLinkerConfig ~=
      (_.withESFeatures(_.withESVersion(esVersion))),
    Test / scalaJSLinkerConfig ~=
      (_.withESFeatures(_.withESVersion(esVersion))),
    Compile / fastLinkJS / contentLinker := CacheBox(),
    Compile / fullLinkJS / contentLinker := CacheBox(),
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
    val content   = linkContent(link).value
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
          Seq(linked, content),
          assets,
          runner,
          log,
        ),
      )
  }

  /**
    * Links the content scripts' own bundle, beside the main one that `link`
    * links and at the same stage, from the same code but from their own entry
    * point, so that it holds only what they reach. It is tagged as a link, as
    * Scala.js's own are, so that a build that limits how many link at once
    * counts it.
    */
  private def linkContent
    (link: TaskKey[Attributed[Report]])
    : Def.Initialize[Task[File]] = Def
    .task {
      val implementation = (Compile / scalaJSLinkerImpl).value
      val config         = (Compile / link / scalaJSLinkerConfig)
        .value
        .withOutputPatterns(OutputPatterns.fromJSFile(Assembly.contentBundle))
      val linker = (Compile / link / contentLinker)
        .value
        .ensure(implementation.clearableLinker(config))
      val ir        = (Compile / scalaJSIR).value.data
      val linked    = (Compile / link / scalaJSLinkerOutputDirectory).value
      val log       = LinkerLog(streams.value.log)
      val directory = linked.getParentFile / s"${ linked.getName }-content"
      val entry     = ModuleInitializer.mainMethodWithArgs(contentEntry, "main")
      IO.createDirectory(directory)
      val output = implementation.outputDirectory(directory.toPath)
      try
        Await.result(
          linker.link(ir, Seq(entry), output, log),
          Duration.Inf,
        )
      catch
        case _: LinkingException => throw MessageOnlyException(
            "The content scripts' bundle failed to link.",
          )
      directory
    }
    .tag(ScalaJSTags.Link)

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
