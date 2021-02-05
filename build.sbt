val scala3Version = "3.0.0-M3"

lazy val root = project
  .in(file("."))
  .settings(
    name := "load-balancer",
    version := "0.1.0",

    scalaVersion := scala3Version,

    libraryDependencies += "org.scalameta" %% "munit" % "0.7.21" % Test,

    testFrameworks += new TestFramework("munit.Framework")
  )
