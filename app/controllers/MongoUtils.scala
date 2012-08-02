package controllers

import org.asyncmongo.api._
import org.asyncmongo.gridfs._
import play.api.libs.concurrent._
import play.api.libs.iteratee._
import play.api.mvc._
import scala.collection.generic.CanBuildFrom
import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.util.Duration
import scala.concurrent.util.duration._
import scala.concurrent.util.DurationInt

trait MongoController {
  self :Controller =>

  val timeout :Duration = new DurationInt(1).seconds

  def MongoPromiseResult(whenReady: => Promise[Result])(implicit connection: MongoConnection, ec: ExecutionContext) = {
    Async {
      connection.waitForPrimary(timeout).flatMap(e => {println(e); whenReady})
    }
  }

  def MongoFutureResult(whenReady: => Future[Result])(implicit connection: MongoConnection, ec: ExecutionContext) = {
    Async {
      connection.waitForPrimary(timeout).flatMap(e => whenReady)
    }
  }

  def collect[M[_], T](futureCursor: Future[Cursor[T]])(implicit cbf: CanBuildFrom[M[_], T, M[T]], ec: ExecutionContext) = {
    Cursor.enumerate(futureCursor) |>>> Iteratee.fold(cbf.apply) { (builder, t :T) => builder += t }.map(_.result)
  }

  def serve(foundFile: Future[Cursor[ReadFileEntry]])(implicit ec: ExecutionContext) = {
    foundFile.map { cursor =>
      // there is a match
      if(cursor.iterator.hasNext) {
        val file = cursor.iterator.next
        SimpleResult(
          // prepare the header
          header = ResponseHeader(200, Map(
              CONTENT_LENGTH -> ("" + file.length),
              CONTENT_DISPOSITION -> ("attachment; filename=\"" + file.filename + "\""),
              CONTENT_TYPE -> file.contentType.getOrElse("application/octet-stream")
          )),
          // give Play this file enumerator
          body = file.enumerate
        )
      } else NotFound
    }
  }

  def gridFSBodyParser(gfs: GridFS)(implicit ec: ExecutionContext) :BodyParser[Seq[Promise[PutResult]]] = BodyParsers.parse.Multipart.multipartParser { headers =>
    val filename = headers.get("content-disposition").flatMap(_.split(';').map(_.trim).find(_.startsWith("filename=")).map(_.drop("filename=\"".length).dropRight(1)))
    val contentType = headers.get("content-type").map(_.trim)
    filename.map { name =>
      val mongoReadyPromise = gfs.db.connection.waitForPrimary(timeout)
      val promiseOfIteratee = mongoReadyPromise.map { _ =>
        gfs.save(name, None, contentType)
      }
      Iteratee.flatten(promiseOfIteratee)
    }.getOrElse(Done.apply(null, Input.Empty)) // if no filename, we drop the upload
  }
}