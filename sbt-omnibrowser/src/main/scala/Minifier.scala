package com.alecdorrington.omnibrowser.plugin

import org.scalajs.linker.interface.ESVersion
import sbt.{*, given}
import sbt.internal.util.MessageOnlyException
import scala.sys.process.{Process, ProcessLogger}
import scala.util.Properties

/**
  * Minifies a release's bundles with esbuild, from npm, as Scala.js advises
  * since it stopped applying the Closure Compiler: its own minifier shortens
  * only the names of properties, which leaves a large extension's main bundle
  * bigger than Firefox Add-ons will parse.
  */
private[plugin] object Minifier:

  /** esbuild, at a fixed version, so that a release builds the same anywhere. */
  private val esbuild = "esbuild@0.28.2"

  /**
    * Copies each of `directories` beside it, with its bundles minified for
    * `esVersion`, and gives the copies.
    */
  def minify
    (
      directories: Seq[File],
      esVersion: ESVersion,
      log: Logger,
    )
    : Seq[File] = directories.map(minified(_, esVersion, log))

  private def minified
    (
      directory: File,
      esVersion: ESVersion,
      log: Logger,
    )
    : File =
    val copy = directory.getParentFile / s"${ directory.getName }-minified"
    IO.delete(copy)
    run(bundles(directory), copy, esVersion, log)
    copy

  private def bundles(directory: File): Seq[File] =
    (directory * "omnibrowser*.js").get()

  /** Minifies `bundles`, which share a directory, into `into`. */
  private def run
    (
      bundles: Seq[File],
      into: File,
      esVersion: ESVersion,
      log: Logger,
    )
    : Unit =
    val exit = Process(
      command(bundles, into, esVersion),
    ).!(ProcessLogger(log.info(_), log.warn(_)))
    if exit != 0 then
      throw MessageOnlyException(
        s"esbuild failed to minify the bundles, with exit code $exit.",
      )

  private def command
    (
      bundles: Seq[File],
      into: File,
      esVersion: ESVersion,
    )
    : Seq[String] = npx ++ Seq("--yes", esbuild) ++
    bundles.map(_.getAbsolutePath) ++ Seq(
      s"--outdir=${ into.getAbsolutePath }",
      "--minify",
      s"--target=${ target(esVersion) }",
      // Text in other scripts as it is, not escaped: browsers read extensions'
      // scripts as UTF-8, and esbuild still escapes lone surrogates.
      "--charset=utf8",
      "--log-level=warning",
    )

  /** esbuild's name for `version`, as `es2021` for ECMAScript 2021. */
  private def target(version: ESVersion): String =
    s"es${ version.name.stripPrefix("ECMAScript ") }"

  /** npx, which Windows runs through its shell, as a script. */
  private val npx: Seq[String] =
    if Properties.isWin then Seq("cmd", "/c", "npx") else Seq("npx")
