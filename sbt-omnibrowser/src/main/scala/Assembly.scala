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
    * the bundles linked into `linked` and the assets in `assets`.
    */
  def into
    (
      directory: File,
      linked: Seq[File],
      assets: File,
      runner: File,
      log: Logger,
    )
    : File =
    IO.delete(directory)
    if assets.isDirectory then IO.copyDirectory(assets, directory)
    IO.copy(
      linked.flatMap(bundles).map(file => file -> directory / file.getName),
    )
    describe(directory, runner, log)
    directory

  /** The bundles linked into `linked`, and their source maps. */
  private def bundles(linked: File): Seq[File] = (linked * "omnibrowser*.js*")
    .get()

  /**
    * Has the bundle describe the extension, through `runner`, which writes the
    * files it describes into `directory`.
    */
  private def describe(directory: File, runner: File, log: Logger): Unit =
    val exit = Process(
      command(directory, runner),
    ).!(ProcessLogger(report(log), log.error(_)))
    if exit != 0 then
      throw MessageOnlyException(
        s"Could not build the extension for ${ target.id }.",
      )

  /** Logs a line that the runner printed, as a warning if it says it is one. */
  private def report(log: Logger)(line: String): Unit = line match
    case s"warning: $warning" => log.warn(s"${ target.id }: $warning")
    case other                => log.info(other)

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

  /** The name of the main bundle, which the background and pages load. */
  val bundle = "omnibrowser.js"

  /** The name of the bundle that the content scripts load, of their code alone. */
  val contentBundle = "omnibrowser-content.js"

  /**
    * The size from which Firefox Add-ons rejects a file that it parses: 5 MiB,
    * as its linter has it.
    */
  private val firefoxLimit = 5L * 1024 * 1024

  /**
    * The extensions of the files that Firefox Add-ons parses, as its linter
    * does.
    */
  private val parsed = Set("html", "htm", "js", "mjs", "json")

  /**
    * Fails if the store of `target` would reject the extension laid out in
    * `directory`, as Firefox Add-ons does one with a file too large to parse.
    */
  def check(target: Target, directory: File): Unit =
    val large = if target == Target.Firefox then tooLarge(directory) else Seq()
    if large.nonEmpty then
      throw MessageOnlyException(
        "Firefox Add-ons rejects files of 5 MiB or more, as too large to parse: " +
          large.mkString(", ") + ".",
      )

  /** The files laid out in `directory` that Firefox Add-ons would not parse. */
  private def tooLarge(directory: File): Seq[String] = Path
    .allSubpaths(directory)
    .toSeq
    .collect:
      case (file, path) if parsed(file.ext) && file.length >= firefoxLimit =>
        f"$path (${ file.length / 1048576.0 }%.1f MiB)"
    .sorted

  /** Deletes the zips in `directory`, such as those of earlier versions. */
  def clearZips(directory: File): Unit = IO.delete((directory * "*.zip").get())

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
      |for (const warning of globalThis.__omnibrowserWarnings ?? []) console.log(`warning: ${warning}`);
      |
      |for (const [name, content] of Object.entries(files)) {
      |  const file = path.join(directory, name);
      |  fs.mkdirSync(path.dirname(file), { recursive: true });
      |  fs.writeFileSync(file, content);
      |}
      |
      |const manifest = JSON.parse(fs.readFileSync(path.join(directory, "manifest.json"), "utf8"));
      |if ((manifest.content_scripts ?? []).length === 0)
      |  for (const name of ["omnibrowser-content.js", "omnibrowser-content.js.map"])
      |    fs.rmSync(path.join(directory, name), { force: true });
      |const pages = Object.keys(files).filter((name) => name.endsWith(".html"));
      |const missing = [...named(manifest), ...pages.flatMap((page) => linked(files[page]))]
      |  .filter((name) => !fs.existsSync(path.join(directory, name)));
      |if (missing.length > 0) fail(`The extension names files that do not exist: ${missing.join(", ")}.`);
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
      |    ...(manifest.content_scripts ?? []).flatMap((script) => [...(script.js ?? []), ...(script.css ?? [])]),
      |  ].filter((name) => name !== undefined);
      |}
      |
      |/** The files of the extension that a page loads, such as its stylesheets. */
      |function linked(html) {
      |  return [...html.matchAll(/(?:src|href)="([^"]+)"/g)]
      |    .map((found) => found[1])
      |    .filter((name) => !/^([a-z][a-z0-9+.-]*:|\/\/|#)/i.test(name));
      |}
      |
      |function fail(message) {
      |  console.error(message);
      |  process.exit(1);
      |}
      |""".stripMargin
