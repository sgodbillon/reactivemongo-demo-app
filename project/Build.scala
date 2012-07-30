import sbt._
import Keys._
import PlayProject._

object ApplicationBuild extends Build {

    val appName         = "mongo-app"
    val appVersion      = "1.0-SNAPSHOT"

    val appDependencies = Seq(
	"org.asyncmongo" %% "mongo-async-driver" % "0.1-SNAPSHOT",
	"play.modules.mongodb" %% "play2-mongodb-async" % "0.1-SNAPSHOT"
    )

    val main = PlayProject(appName, appVersion, appDependencies, mainLang = SCALA).settings(
	resolvers += "sgodbillon" at "https://bitbucket.org/sgodbillon/repository/raw/master/snapshots/"
    )

}
