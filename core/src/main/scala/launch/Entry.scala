package com.alecdorrington.omnibrowser.launch

/**
  * The entry point of an extension's main bundle, which starts its background
  * or a page, whichever the bootstrap loaded before the bundle names, or
  * describes the extension to the build.
  */
object Entry:

  def main(args: Array[String]): Unit = Bootstrap
    .current
    .foreach(Bootstrap.startExtension)

/**
  * The entry point of the bundle of an extension's content scripts, which
  * starts the one that the bootstrap loaded before the bundle names.
  */
object ContentEntry:

  def main(args: Array[String]): Unit = Bootstrap
    .current
    .foreach(Bootstrap.startContent)
