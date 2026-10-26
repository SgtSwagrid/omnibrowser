package com.alecdorrington.omnibrowser.launch

import com.alecdorrington.omnibrowser.{Android, BrowserExtension}

/**
  * What Firefox for Android lacks of what desktop Firefox has, as
  * [[https://github.com/mdn/browser-compat-data MDN's compatibility data]]
  * records it, so that a build for Android can warn of it.
  */
private[omnibrowser] object AndroidSupport:

  /** The permissions that Firefox for Android does not grant. */
  val missingPermissions: Set[String] = Set(
    "bookmarks",
    "captivePortal",
    "contextMenus",
    "contextualIdentities",
    "devtools",
    "find",
    "history",
    "identity",
    "menus",
    "nativeMessaging",
    "pkcs11",
    "search",
    "sessions",
    "tabHide",
    "theme",
  )

  /** The permissions that Firefox for Android cannot grant while it runs. */
  val missingOptionalPermissions: Set[String] = Set(
    "bookmarks",
    "browsingData",
    "devtools",
    "find",
    "history",
    "management",
    "menus",
    "nativeMessaging",
    "pkcs11",
    "privacy",
    "proxy",
    "sessions",
    "tabHide",
    "userScripts",
  )

  /**
    * Warnings of what `extension` asks for that Firefox for Android lacks, if
    * the extension supports it.
    */
  def warnings(extension: BrowserExtension[?]): Seq[String] =
    extension.android match
      case Android.Unsupported  => Seq.empty
      case _: Android.Supported => warnings(
          extension.permissions,
          extension.optionalPermissions.names,
        )

  /**
    * Warnings of the `permissions` and `optional` permissions that Firefox for
    * Android lacks.
    */
  def warnings
    (
      permissions: Set[String],
      optional: Set[String],
    )
    : Seq[String] = missing(
    permissions,
    missingPermissions,
    "permission",
  ) ++ missing(
    optional,
    missingOptionalPermissions,
    "optional permission",
  )

  private def missing
    (
      asked: Set[String],
      lacking: Set[String],
      kind: String,
    )
    : Seq[String] = asked
    .intersect(lacking)
    .toSeq
    .sorted
    .map(name =>
      s"Firefox for Android lacks the $kind $name, which the extension asks " +
        "for. Its APIs will be missing there, or else declare " +
        "`android = Android.Unsupported`.",
    )
