package com.alecdorrington.omnibrowser

import com.alecdorrington.omnibrowser.Permission.*
import munit.FunSuite

class PermissionsSuite extends FunSuite:

  test(
    "A permission is named after its type, an intersection names each of its permissions, and `Any` names none.",
  ):
    assertEquals(
      summon[Permissions[ActiveTab]].names,
      Set("activeTab"),
    )
    assertEquals(
      summon[Permissions[Storage & Tabs & ActiveTab]].names,
      Set("storage", "tabs", "activeTab"),
    )
    assertEquals(
      summon[Permissions[Any]].names,
      Set.empty,
    )

  test("A type that is not a permission is rejected."):
    assert(compileErrors("summon[Permissions[String]]").contains(
      "String is not a permission.",
    ))

  test(
    "An extension's permissions grant the APIs that they cover, and only those.",
  ):
    assertNoDiff(
      compileErrors(
        "{ import Granting.given; (browser: Browser) => browser.storage }",
      ),
      "",
    )
    val errors = compileErrors(
      "{ import Withholding.given; (browser: Browser) => browser.storage }",
    )
    assert(
      errors.contains("The extension lacks the permission"),
      errors,
    )

/** An extension that may use storage. */
object Granting extends BrowserExtension[Storage & Tabs]:
  override val name = "Granting"

/** An extension that may not use storage. */
object Withholding extends BrowserExtension[Tabs]:
  override val name = "Withholding"
