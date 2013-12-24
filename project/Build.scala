import sbt._
import Keys._

object ApplicationBuild extends Build {

  val appName = "mongo-app"
  val appVersion = "1.0-SNAPSHOT"

  scalaVersion := "2.10.2"

  val appDependencies = Seq(
    "org.reactivemongo" %% "play2-reactivemongo" % "0.10.0")

  val main = play.Project(appName, appVersion, appDependencies).settings(
    resolvers += "Sonatype OSS Releases" at "http://oss.sonatype.org/content/repositories/releases"
      // settings
  )

}
