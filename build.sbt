ThisBuild / organization := "io.github.ccerdadiaz"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.6.4"

lazy val root = (project in file("."))
  .settings(
    name := "saga-blind",
    libraryDependencies ++= Seq(
      // saga-graph — compensation engine
      // "io.github.ccerdadiaz" %% "saga-graph" % "0.1.0",  // when published
      // JSON — pool payload
      "com.lihaoyi"   %% "ujson"       % "3.3.1",
      // minimal http rest
      "com.lihaoyi" %% "cask" % "0.9.4",
      // JSONPath — compensation extractors
      "com.jayway.jsonpath" % "json-path" % "3.0.0",
      // SQLite — WAL store
      "org.xerial"     % "sqlite-jdbc" % "3.47.1.0",
      // Testing
      "org.scalatest" %% "scalatest"   % "3.2.19" % Test,
    ),
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Xfatal-warnings",
    ),
  )
