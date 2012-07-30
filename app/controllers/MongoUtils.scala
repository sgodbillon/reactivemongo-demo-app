package controllers

import akka.dispatch.Future
import akka.util.Timeout
import akka.util.duration._
import org.asyncmongo.api._
import org.asyncmongo.gridfs._
import play.api.libs.concurrent._
import play.api.libs.iteratee._
import play.api.mvc._
import scala.collection.generic.CanBuildFrom

trait MongoController {
  self :Controller =>

  def MongoPromiseResult(whenReady: => Promise[Result])(implicit connection: MongoConnection) = {
    Async {
      implicit val timeout = Timeout(1 seconds)
      new AkkaPromise(connection.waitForPrimary(timeout)).flatMap(e => {println(e); whenReady})
    }
  }
  
  def MongoFutureResult(whenReady: => Future[Result])(implicit connection: MongoConnection) = {
    Async {
      implicit val timeout = Timeout(1 seconds)
      new AkkaPromise(connection.waitForPrimary(timeout).flatMap(e => {println(e); whenReady}))
    }
  }

  def collect[M[_], T](futureCursor: Future[Cursor[T]])(implicit cbf: CanBuildFrom[M[_], T, M[T]]) = {
    Cursor.enumerate(futureCursor) |>>> Iteratee.fold(cbf.apply) { (builder, t :T) => builder += t }.map(_.result)
  }

  def gridFSBodyParser(gfs: GridFS) :BodyParser[Seq[Promise[PutResult]]] = BodyParsers.parse.Multipart.multipartParser { headers =>
    val filename = headers.get("content-disposition").flatMap(_.split(';').map(_.trim).find(_.startsWith("filename=")).map(_.drop("filename=\"".length).dropRight(1)))
    val contentType = headers.get("content-type").map(_.trim)

    filename.map { name =>
      val mongoReadyPromise = new AkkaPromise(gfs.db.connection.waitForPrimary(Timeout(1 seconds)))
      val promiseOfIteratee = mongoReadyPromise.map { _ =>
        gfs.save(name, None, contentType)
      }
      Iteratee.flatten(promiseOfIteratee)
    }.getOrElse(Done.apply(null, Input.Empty)) // if no filename, we drop the upload
  }
}