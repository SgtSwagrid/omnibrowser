package com.alecdorrington.omnibrowser.testkit

import scala.util.matching.Regex

/**
  * The browser's
  * [[https://developer.mozilla.org/docs/Mozilla/Add-ons/WebExtensions/Match_patterns match patterns]],
  * which decide the pages that a content script is injected into.
  */
private[testkit] object MatchPattern:

  /** Whether `url` matches `pattern`. */
  def matches(pattern: String, url: String): Boolean =
    if pattern == "<all_urls>" then url.matches("(https?|wss?|file|ftp)://.*")
    else regex(pattern).exists(_.matches(url))

  private def regex(pattern: String): Option[Regex] = pattern match
    case s"$scheme://$host/$path" =>
      Some(s"${ schemes(scheme) }://${ hosts(host) }/${ paths(path) }".r)
    case _ => None

  /** The schemes that `scheme` matches, where a wildcard means the web's. */
  private def schemes(scheme: String): String =
    if scheme == "*" then "(https?|wss?)" else Regex.quote(scheme)

  /** The hosts that `host` matches, which may begin with a wildcard label. */
  private def hosts(host: String): String = host match
    case "*"          => "[^/]+"
    case s"*.$domain" => s"([^/]+\\.)?${ Regex.quote(domain) }"
    case exact        => Regex.quote(exact)

  /** The paths that `path` matches, in which each wildcard means anything. */
  private def paths(path: String): String = path
    .split("\\*", -1)
    .map(Regex.quote)
    .mkString(".*")
