import play.PlayImport.PlayKeys._

name := "reactivemongo-demo-app"

version := "0.11.10"

scalaVersion := "2.11.7"

libraryDependencies += "org.reactivemongo" %% "play2-reactivemongo" % "0.11.10"

routesGenerator := InjectedRoutesGenerator

lazy val root = (project in file(".")).enablePlugins(PlayScala)
