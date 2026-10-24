package com.alecdorrington.omnibrowser.launch

/**
  * The entry point of an extension's bundle, which starts the part of the
  * extension that the bootstrap loaded before the bundle names, if any.
  */
object Entry:

  def main(args: Array[String]): Unit = Bootstrap
    .current
    .foreach(Bootstrap.start)
