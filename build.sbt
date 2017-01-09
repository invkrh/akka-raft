name := "akka-raft"

version := "0.1.0"

scalaVersion := "2.12.1"

libraryDependencies ++=
  Seq(
    "org.slf4j" % "slf4j-api" % "1.7.22",
    "org.slf4j" % "slf4j-log4j12" % "1.7.22",
    "com.typesafe.akka" %% "akka-actor" % "2.4.16",
    "com.typesafe.akka" %% "akka-testkit" % "2.4.16" % "test",
    "org.scalatest" %% "scalatest" % "3.0.1" % "test"
  )
