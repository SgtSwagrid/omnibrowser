package com.alecdorrington.omnibrowser.testkit

import munit.FunSuite

class MatchPatternSuite extends FunSuite:

  test(
    "A wildcard matches HTTP and HTTPS as the scheme, the domain and its subdomains as a label, and anything in the path, and `<all_urls>` every web page.",
  ):
    Seq(
      ("*://example.com/", "http://example.com/", true),
      ("*://example.com/", "https://example.com/", true),
      ("*://example.com/", "file://example.com/", false),
      ("https://*.example.com/", "https://example.com/", true),
      ("https://*.example.com/", "https://a.b.example.com/", true),
      ("https://*.example.com/", "https://notexample.com/", false),
      ("https://example.com/a*z", "https://example.com/a/b/z", true),
      ("https://example.com/a*z", "https://example.com/a/b/y", false),
      ("https://example.com/", "https://example.com/page", false),
      ("<all_urls>", "https://example.com/page", true),
      ("<all_urls>", "about:blank", false),
    ).foreach((pattern, url, expected) =>
      assertEquals(
        MatchPattern.matches(pattern, url),
        expected,
        s"$pattern, $url",
      ),
    )
