package com.alecdorrington.omnibrowser.plugin

import sbt.{*, given}
import sbt.internal.util.MessageOnlyException
import scala.sys.process.{Process, ProcessLogger}

/**
  * The layout of an extension for the browser `target`, as the object named
  * `extension` defines it, at `version`.
  */
final case class Assembly
  (
    extension: String,
    target: Target,
    version: String,
    geckoId: String,
  ):

  /**
    * Lays out the extension in `directory`, replacing whatever was there, from
    * the bundle linked into `linked` and the assets in `assets`.
    */
  def into
    (
      directory: File,
      linked: File,
      assets: File,
      runner: File,
      log: Logger,
    )
    : File =
    IO.delete(directory)
    if assets.isDirectory then IO.copyDirectory(assets, directory)
    IO.copy(bundle(linked).map(file => file -> directory / file.getName))
    describe(directory, runner, log)
    directory

  /** The bundle, and its source map. */
  private def bundle(linked: File): Seq[File] =
    (linked * s"${ Assembly.bundle }*").get()

  /**
    * Has the bundle describe the extension, through `runner`, which writes the
    * files it describes into `directory`.
    */
  private def describe(directory: File, runner: File, log: Logger): Unit =
    val exit = Process(
      command(directory, runner),
    ).!(ProcessLogger(log.info(_), log.error(_)))
    if exit != 0 then
      throw MessageOnlyException(
        s"Could not build the extension for ${ target.id }.",
      )

  private def command(directory: File, runner: File): Seq[String] = Seq(
    "node",
    runner.getAbsolutePath,
    directory.getAbsolutePath,
    extension,
    target.id,
    version,
    geckoId,
  )

object Assembly:

  /** The name of the linked bundle, which every part of an extension loads. */
  val bundle = "omnibrowser.js"

  /** Zips the extension laid out in `directory` into `zip`. */
  def zip(directory: File, zip: File): File =
    IO.zip(Path.allSubpaths(directory), zip, None)
    zip

/**
  * The Node.js script that runs an extension's bundle to have it describe the
  * extension, then writes the files that it describes, and checks that every
  * file its manifest names exists.
  */
private[plugin] object Runner:

  /** Writes the script into `directory`. */
  def write(directory: File): File =
    val file = directory / "omnibrowser-describe.js"
    IO.write(file, script)
    file

  private val script = """"use strict";
      |const fs = require("fs");
      |const path = require("path");
      |
      |const [directory, extension, target, version, geckoId] = process.argv.slice(2);
      |
      |globalThis.__omnibrowser = { extension, role: "describe", target, version, geckoId };
      |require(path.join(directory, "omnibrowser.js"));
      |
      |const files = globalThis.__omnibrowserFiles;
      |if (!files) fail(`The bundle does not describe the extension ${extension}.`);
      |
      |for (const [name, content] of Object.entries(files)) {
      |  const file = path.join(directory, name);
      |  fs.mkdirSync(path.dirname(file), { recursive: true });
      |  fs.writeFileSync(file, content);
      |}
      |
      |const manifest = JSON.parse(fs.readFileSync(path.join(directory, "manifest.json"), "utf8"));
      |const missing = named(manifest).filter((name) => !fs.existsSync(path.join(directory, name)));
      |if (missing.length > 0) fail(`The manifest names files that do not exist: ${missing.join(", ")}.`);
      |
      |/** Every file that the manifest names. */
      |function named(manifest) {
      |  const background = manifest.background ?? {};
      |  return [
      |    ...Object.values(manifest.icons ?? {}),
      |    ...Object.values(manifest.action?.default_icon ?? {}),
      |    manifest.action?.default_popup,
      |    manifest.options_ui?.page,
      |    background.service_worker,
      |    ...(background.scripts ?? []),
      |    ...(manifest.content_scripts ?? []).flatMap((script) => script.js ?? []),
      |  ].filter((name) => name !== undefined);
      |}
      |
      |function fail(message) {
      |  console.error(message);
      |  process.exit(1);
      |}
      |""".stripMargin
