package com.alecdorrington.omnibrowser.testkit

import munit.FunSuite

class MatchPatternSuite extends FunSuite:

  private def check
    (
      pattern: String,
      url: String,
      expected: Boolean,
    )
    : Unit = assertEquals(
    MatchPattern.matches(pattern, url),
    expected,
    s"$pattern, $url",
  )

  test("A wildcard scheme matches HTTP and HTTPS, but not files."):
    check(
      "*://example.com/",
      "http://example.com/",
      true,
    )
    check(
      "*://example.com/",
      "https://example.com/",
      true,
    )
    check(
      "*://example.com/",
      "file://example.com/",
      false,
    )

  test("A wildcard label matches the domain and each of its subdomains."):
    check(
      "https://*.example.com/",
      "https://example.com/",
      true,
    )
    check(
      "https://*.example.com/",
      "https://a.b.example.com/",
      true,
    )
    check(
      "https://*.example.com/",
      "https://notexample.com/",
      false,
    )

  test("A wildcard in the path matches anything there."):
    check(
      "https://example.com/a*z",
      "https://example.com/a/b/z",
      true,
    )
    check(
      "https://example.com/a*z",
      "https://example.com/a/b/y",
      false,
    )
    check(
      "https://example.com/",
      "https://example.com/page",
      false,
    )

  test("Every web page matches `<all_urls>`."):
    check(
      "<all_urls>",
      "https://example.com/page",
      true,
    )
    check("<all_urls>", "about:blank", false)
