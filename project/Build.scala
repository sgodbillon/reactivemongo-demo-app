import sbt._
import Keys._
import play.Play.autoImport._
import PlayKeys._

object ApplicationBuild extends Build {

  val appName = "mongo-app"
  val appVersion = "1.0-SNAPSHOT"

  val appDependencies = Seq(
    "org.reactivemongo" %% "play2-reactivemongo" % "0.10.5.0.akka23")

  val main = Project(appName, file(".")).enablePlugins(play.PlayScala).settings(
    resolvers +=  Resolver.sonatypeRepo("releases"),
    version := appVersion,
    scalaVersion := "2.11.4",
    libraryDependencies ++= appDependencies
  )

}

