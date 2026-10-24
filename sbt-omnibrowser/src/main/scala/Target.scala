package com.alecdorrington.omnibrowser.plugin

/** A browser that an extension may be built for. */
enum Target(val id: String):

  case Chrome extends Target("chrome")

  case Firefox extends Target("firefox")
