package com.alecdorrington.omnibrowser

/**
  * The data that an extension collects or sends elsewhere: the `required` kinds
  * without which it cannot work, and the `optional` kinds that the user may
  * refuse. Firefox shows them to the user on installing the extension, and its
  * add-on store refuses extensions that do not declare them.
  */
final case class DataCollection
  (
    required: Set[PersonalData],
    optional: Set[DataKind] = Set.empty,
  )

object DataCollection:

  /** No data at all. */
  val none: DataCollection = DataCollection(Set.empty)

/** A kind of data that an extension may collect, as Firefox names it. */
sealed trait DataKind(private[omnibrowser] val id: String)

object DataKind:

  /** Technical data, and data on how the extension is used. */
  case object TechnicalAndInteraction
    extends DataKind("technicalAndInteraction")

/** A kind of personal data, which an extension may require. */
enum PersonalData(id: String) extends DataKind(id):

  /** Usernames, passwords and other credentials. */
  case AuthenticationInfo extends PersonalData("authenticationInfo")

  case BookmarksInfo extends PersonalData("bookmarksInfo")

  /** The pages that the user visits, and when. */
  case BrowsingActivity extends PersonalData("browsingActivity")

  case FinancialAndPaymentInfo extends PersonalData("financialAndPaymentInfo")

  case HealthInfo extends PersonalData("healthInfo")

  case LocationInfo extends PersonalData("locationInfo")

  /** Emails, messages and other communications. */
  case PersonalCommunications extends PersonalData("personalCommunications")

  /** Names, addresses and other details that identify the user. */
  case PersonallyIdentifyingInfo
    extends PersonalData("personallyIdentifyingInfo")

  case SearchTerms extends PersonalData("searchTerms")

  /** What the user does on a page, such as their clicks and scrolling. */
  case WebsiteActivity extends PersonalData("websiteActivity")

  /** The content of the pages that the user visits. */
  case WebsiteContent extends PersonalData("websiteContent")
