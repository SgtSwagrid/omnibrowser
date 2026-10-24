package com.alecdorrington.omnibrowser

import scala.annotation.implicitNotFound
import scala.quoted.*

/**
  * A permission that an extension may request, named in its manifest after its
  * type, as `activeTab` for [[Permission.ActiveTab]].
  */
sealed trait Permission

object Permission:

  /** Use of the extension's [[Browser.Storage]]. */
  sealed trait Storage extends Permission

  /**
    * The URL and title of every [[Tab]], rather than of only those whose pages
    * the extension may otherwise access.
    */
  sealed trait Tabs extends Permission

  /** Access to the active tab whenever the user invokes the extension. */
  sealed trait ActiveTab extends Permission

/**
  * The manifest names of the permissions in `P`, an intersection of
  * [[Permission]] types, or `Any` for none.
  */
final case class Permissions[P](names: Set[String])

object Permissions:

  /** The names of the permissions in `P`, as the compiler finds them. */
  inline given derived[P]: Permissions[P] = ${ derive[P] }

  /** The names of the permissions in `Q`, such as `Permission.Tabs`. */
  inline def of[Q]: Permissions[Q] = derived[Q]

  /** No permissions at all. */
  val none: Permissions[Any] = Permissions(Set.empty)

  private def derive[P : Type](using q: Quotes): Expr[Permissions[P]] =
    val permissions = Expr(names(q.reflect.TypeRepr.of[P]))
    '{ Permissions[P]($permissions) }

  private def names(using q: Quotes)(tpe: q.reflect.TypeRepr): Set[String] =
    import q.reflect.*
    tpe.dealias match
      case AndType(left, right)                   => names(left) ++ names(right)
      case none if none =:= TypeRepr.of[Any]      => Set.empty
      case permission if isPermission(permission) =>
        Set(manifestName(permission.typeSymbol.name))
      case other =>
        report.errorAndAbort(s"${ other.show } is not a permission.")

  private def isPermission(using q: Quotes)(tpe: q.reflect.TypeRepr): Boolean =
    import q.reflect.*
    tpe <:< TypeRepr.of[Permission] && tpe.typeSymbol.flags.is(Flags.Sealed) &&
    tpe.typeSymbol != TypeRepr.of[Permission].typeSymbol

  private def manifestName(name: String): String = name.head.toLower +:
    name.tail

/**
  * Evidence that an extension has the permission `Q`, which the APIs that `Q`
  * grants require.
  */
@implicitNotFound(
  "The extension lacks the permission ${Q}. Add it to the extension's type, as in `BrowserExtension[... & ${Q}]`.",
)
final class Granted[Q] private[omnibrowser] ()
