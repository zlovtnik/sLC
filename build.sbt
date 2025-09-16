name := "health-check-log-aggregator"

version := "0.1.0"

scalaVersion := "2.13.12"

libraryDependencies ++= Seq(
  // Akka HTTP for server
  "com.typesafe.akka" %% "akka-http" % "10.5.2",
  "com.typesafe.akka" %% "akka-stream" % "2.8.5",
  "com.typesafe.akka" %% "akka-actor-typed" % "2.8.5",

  // HTTP client
  "com.softwaremill.sttp.client3" %% "core" % "3.9.1",
  "com.softwaremill.sttp.client3" %% "circe" % "3.9.1",
  "com.softwaremill.sttp.client3" %% "zio" % "3.9.1",

  // JSON
  "io.circe" %% "circe-core" % "0.14.6",
  "io.circe" %% "circe-generic" % "0.14.6",
  "io.circe" %% "circe-parser" % "0.14.6",

  // Logging
  "ch.qos.logback" % "logback-classic" % "1.4.11",
  "org.fusesource.jansi" % "jansi" % "2.4.0",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5",

  // Configuration
  "com.typesafe" % "config" % "1.4.3",

  // ZIO
  "dev.zio" %% "zio" % "2.0.21",
  "dev.zio" %% "zio-logging" % "2.1.16",
  "dev.zio" %% "zio-logging-slf4j" % "2.1.16",
  "dev.zio" %% "zio-config" % "4.0.0-RC16",
  "dev.zio" %% "zio-config-magnolia" % "4.0.0-RC16",
  "dev.zio" %% "zio-config-typesafe" % "4.0.0-RC16",

  // Cats
  "org.typelevel" %% "cats-core" % "2.10.0",
  "org.typelevel" %% "cats-effect" % "3.4.8",

  // ZIO-Cats integration
  "dev.zio" %% "zio-interop-cats" % "23.0.0.8",

  // Test dependencies
  "org.scalatest" %% "scalatest" % "3.2.17" % Test,
  "com.typesafe.akka" %% "akka-http-testkit" % "10.5.2" % Test,
  "com.typesafe.akka" %% "akka-testkit" % "2.8.5" % Test
)

addCommandAlias("lint", "lintUnused")