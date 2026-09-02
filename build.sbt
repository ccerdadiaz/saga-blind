ThisBuild / organization := "io.github.ccerdadiaz"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.6.4"

ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Xfatal-warnings",
)

// ── core ─────────────────────────────────────────────────────────────────────
// The engine — no database driver, no logback.
// Dependencies: ujson (OKV pool), jayway-jsonpath (param mapping), cask (HTTP)

lazy val core = (project in file("core"))
  .settings(
    name := "saga-blind-core",
    libraryDependencies ++= Seq(
      "com.lihaoyi"        %% "ujson"        % "3.3.1",
      "com.jayway.jsonpath" % "json-path"    % "3.0.0",
      "com.lihaoyi"        %% "cask"         % "0.9.4",
      "org.scalatest"      %% "scalatest"    % "3.2.19" % Test,
    ),
  )

// ── store-sqlite ──────────────────────────────────────────────────────────────
// SQLite implementation of WalStore.
// One deployment option — swap for store-postgres in production if needed.

lazy val storeSqlite = (project in file("store-sqlite"))
  .dependsOn(core)
  .settings(
    name := "saga-blind-store-sqlite",
    libraryDependencies ++= Seq(
      "org.xerial"    % "sqlite-jdbc" % "3.47.1.0",
      "org.scalatest" %% "scalatest"  % "3.2.19" % Test,
    ),
  )

// ── examples ──────────────────────────────────────────────────────────────────
// goblin-world and other demos.
// Brings in logback for real logging, sqlite for storage.

lazy val examples = (project in file("examples"))
  .dependsOn(core, storeSqlite)
  .settings(
    name := "saga-blind-examples",
    libraryDependencies ++= Seq(
      "com.typesafe.scala-logging" %% "scala-logging"   % "3.9.5",
      "ch.qos.logback"              % "logback-classic"  % "1.5.6",
      "org.scalatest"              %% "scalatest"        % "3.2.19" % Test,
    ),
  )

lazy val root = (project in file("."))
  .aggregate(core, storeSqlite, examples)
  .settings(
    name := "saga-blind",
    publish / skip := true,
  )
