import play.PlayImport.PlayKeys._

name := "reactivemongo-demo-app"

version := "0.11.1"

scalaVersion := "2.11.6"

libraryDependencies += "org.reactivemongo" %% "play2-reactivemongo" % "0.11.4.play24"

routesGenerator := InjectedRoutesGenerator

lazy val root = (project in file(".")).enablePlugins(PlayScala)
