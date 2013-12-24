package controllers

import org.joda.time.DateTime
import scala.concurrent.Future

import play.api.Logger
import play.api.Play.current
import play.api.mvc._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.modules.reactivemongo.{ MongoController, ReactiveMongoPlugin }

import reactivemongo.api.gridfs.GridFS
import reactivemongo.api.gridfs.Implicits.DefaultReadFileReader
import reactivemongo.api.collections.default.BSONCollection
import reactivemongo.bson._

import models.Article
import models.Article._

object Articles extends Controller with MongoController {

  // get the collection 'articles'
  val collection = db[BSONCollection]("articles")
  // a GridFS store named 'attachments'
  //val gridFS = new GridFS(db, "attachments")
  val gridFS = new GridFS(db)

  // let's build an index on our gridfs chunks collection if none
  gridFS.ensureIndex().onComplete {
    case index =>
      Logger.info(s"Checked index, result is $index")
  }

  // list all articles and sort them
  def index = Action.async { implicit request =>
    // get a sort document (see getSort method for more information)
    val sort = getSort(request)
    // build a selection document with an empty query and a sort subdocument ('$orderby')
    val query = BSONDocument(
      "$orderby" -> sort,
      "$query" -> BSONDocument())
    val activeSort = request.queryString.get("sort").flatMap(_.headOption).getOrElse("none")
    // the cursor of documents
    val found = collection.find(query).cursor[Article]
    // build (asynchronously) a list containing all the articles
    found.collect[List]().map { articles =>
      Ok(views.html.articles(articles, activeSort))
    }.recover {
      case e =>
        e.printStackTrace()
        BadRequest(e.getMessage())
    }
  }

  def showCreationForm = Action {
    Ok(views.html.editArticle(None, Article.form, None))
  }

  def showEditForm(id: String) = Action.async {
    val objectId = new BSONObjectID(id)
    // get the documents having this id (there will be 0 or 1 result)
    val futureArticle = collection.find(BSONDocument("_id" -> objectId)).one[Article]
    // ... so we get optionally the matching article, if any
    // let's use for-comprehensions to compose futures (see http://doc.akka.io/docs/akka/2.0.3/scala/futures.html#For_Comprehensions for more information)
    for {
      // get a future option of article
      maybeArticle <- futureArticle
      // if there is some article, return a future of result with the article and its attachments
      result <- maybeArticle.map { article =>
        import reactivemongo.api.gridfs.Implicits.DefaultReadFileReader
        // search for the matching attachments
        // find(...).toList returns a future list of documents (here, a future list of ReadFileEntry)
        gridFS.find(BSONDocument("article" -> article.id.get)).collect[List]().map { files =>
          val filesWithId = files.map { file =>
            file.id.asInstanceOf[BSONObjectID].stringify -> file
          }
          Ok(views.html.editArticle(Some(id), Article.form.fill(article), Some(filesWithId)))
        }
      }.getOrElse(Future(NotFound))
    } yield result
  }

  def create = Action.async { implicit request =>
    Article.form.bindFromRequest.fold(
      errors => Future.successful(Ok(views.html.editArticle(None, errors, None))),
      // if no error, then insert the article into the 'articles' collection
      article =>
        collection.insert(article.copy(creationDate = Some(new DateTime()), updateDate = Some(new DateTime()))).map(_ =>
          Redirect(routes.Articles.index))
    )
  }

  def edit(id: String) = Action.async { implicit request =>
    Article.form.bindFromRequest.fold(
      errors => Future.successful(Ok(views.html.editArticle(Some(id), errors, None))),
      article => {
        val objectId = new BSONObjectID(id)
        // create a modifier document, ie a document that contains the update operations to run onto the documents matching the query
        val modifier = BSONDocument(
          // this modifier will set the fields 'updateDate', 'title', 'content', and 'publisher'
          "$set" -> BSONDocument(
            "updateDate" -> BSONDateTime(new DateTime().getMillis),
            "title" -> BSONString(article.title),
            "content" -> BSONString(article.content),
            "publisher" -> BSONString(article.publisher)))
        // ok, let's do the update
        collection.update(BSONDocument("_id" -> objectId), modifier).map { _ =>
          Redirect(routes.Articles.index)
        }
      })
  }

  def delete(id: String) = Action.async {
    // let's collect all the attachments matching that match the article to delete
    gridFS.find(BSONDocument("article" -> new BSONObjectID(id))).collect[List]().flatMap { files =>
      // for each attachment, delete their chunks and then their file entry
      val deletions = files.map { file =>
        gridFS.remove(file)
      }
      Future.sequence(deletions)
    }.flatMap { _ =>
      // now, the last operation: remove the article
      collection.remove(BSONDocument("_id" -> new BSONObjectID(id)))
    }.map(_ => Ok).recover { case _ => InternalServerError }
  }

  // save the uploaded file as an attachment of the article with the given id
  def saveAttachment(id: String) = Action.async(gridFSBodyParser(gridFS)) { request =>
    // here is the future file!
    val futureFile = request.body.files.head.ref
    // when the upload is complete, we add the article id to the file entry (in order to find the attachments of the article)
    val futureUpdate = for {
      file <- futureFile
      // here, the file is completely uploaded, so it is time to update the article
      updateResult <- {
        gridFS.files.update(
          BSONDocument("_id" -> file.id),
          BSONDocument("$set" -> BSONDocument("article" -> BSONObjectID(id))))
      }
    } yield updateResult

    futureUpdate.map {
      case _ => Redirect(routes.Articles.showEditForm(id))
    }.recover {
      case e => InternalServerError(e.getMessage())
    }
  }

  def getAttachment(id: String) = Action.async { request =>
    // find the matching attachment, if any, and streams it to the client
    val file = gridFS.find(BSONDocument("_id" -> new BSONObjectID(id)))
    request.getQueryString("inline") match {
      case Some("true") => serve(gridFS, file, CONTENT_DISPOSITION_INLINE)
      case _            => serve(gridFS, file)
    }
  }

  def removeAttachment(id: String) = Action.async {
    gridFS.remove(new BSONObjectID(id)).map(_ => Ok).recover { case _ => InternalServerError }
  }

  private def getSort(request: Request[_]) = {
    request.queryString.get("sort").map { fields =>
      val sortBy = for {
        order <- fields.map { field =>
          if (field.startsWith("-"))
            field.drop(1) -> -1
          else field -> 1
        }
        if order._1 == "title" || order._1 == "publisher" || order._1 == "creationDate" || order._1 == "updateDate"
      } yield order._1 -> BSONInteger(order._2)
      BSONDocument(sortBy)
    }
  }
}
