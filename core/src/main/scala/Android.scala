package com.alecdorrington.omnibrowser

/**
  * Whether Firefox for Android may install an extension, as Firefox's add-on
  * store records it from the extension's manifest.
  */
sealed trait Android

object Android:

  /**
    * Firefox for Android may install the extension, from version `minimum` on
    * if given, such as `"120.0"`.
    */
  final case class Supported(minimum: Option[String] = None) extends Android

  /** Only desktop Firefox may install the extension. */
  case object Unsupported extends Android
