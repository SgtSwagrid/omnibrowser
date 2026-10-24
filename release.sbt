ThisBuild / description :=
  "Write browser extensions in Scala against a typed model of the browser."

ThisBuild / homepage :=
  Some(url("https://alecdorrington.com/omnibrowser"))

ThisBuild / organization         := "com.alecdorrington"
ThisBuild / organizationName     := "SgtSwagrid"
ThisBuild / organizationHomepage := Some(url("https://github.com/SgtSwagrid"))

ThisBuild / versionScheme := Some("strict")

ThisBuild / licenses :=
  List("MIT" -> url("https://opensource.org/licenses/MIT"))

ThisBuild / developers := List(Developer(
  id = "SgtSwagrid",
  name = "Alec Dorrington",
  email = "alecdorrington@gmail.com",
  url = url("https://github.com/SgtSwagrid"),
))
