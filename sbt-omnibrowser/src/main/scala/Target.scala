package com.alecdorrington.omnibrowser.plugin

/** A browser that an extension may be built for. */
enum Target(val id: String):

  /**
    * The browsers built on Chromium, which share its extensions: Chrome, Edge,
    * Opera, Brave, Vivaldi and others.
    */
  case Chromium extends Target("chromium")

  /** Firefox, on desktop and on Android. */
  case Firefox extends Target("firefox")

  /** Safari, on macOS, iOS and iPadOS. */
  case Safari extends Target("safari")
