package controllers

import javax.inject.Inject

import org.joda.time.DateTime

import scala.concurrent.{ Await, Future, duration }, duration.Duration

import play.api.Logger

import play.api.i18n.{ I18nSupport, MessagesApi }
import play.api.mvc.{ Action, Controller, Request }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ Json, JsObject, JsString }

import reactivemongo.api.gridfs.{ GridFS, ReadFile }

import play.modules.reactivemongo.{
  MongoController, ReactiveMongoApi, ReactiveMongoComponents
}

import reactivemongo.play.json._
import reactivemongo.play.json.collection._

import models.Article, Article._

class Articles @Inject() (
  val messagesApi: MessagesApi,
  val reactiveMongoApi: ReactiveMongoApi)
    extends Controller with MongoController with ReactiveMongoComponents {

  import java.util.UUID
  import MongoController.readFileReads

  type JSONReadFile = ReadFile[JSONSerializationPack.type, JsString]

  // get the collection 'articles'
  def collection = reactiveMongoApi.database.
    map(_.collection[JSONCollection]("articles"))

  // a GridFS store named 'attachments'
  //val gridFS = GridFS(db, "attachments")
  private val gridFS = for {
    fs <- reactiveMongoApi.database.map(db =>
      GridFS[JSONSerializationPack.type](db))
    _ <- fs.ensureIndex().map { index =>
      // let's build an index on our gridfs chunks collection if none
      Logger.info(s"Checked index, result is $index")
    }
  } yield fs

  // list all articles and sort them
  def index = Action.async { implicit request =>
    // get a sort document (see getSort method for more information)
    val sort: Option[JsObject] = getSort(request)
    
    // build a selection document with an empty query and a sort subdocument ('$orderby')
    val query = Json.obj(
      "$orderby" -> sort.fold[Json.JsValueWrapper](Json.obj())(identity),
      "$query" -> Json.obj())

    val activeSort = request.queryString.get("sort").
      flatMap(_.headOption).getOrElse("none")

    // the cursor of documents
    val found = collection.map(_.find(query).cursor[Article]())

    // build (asynchronously) a list containing all the articles
    found.flatMap(_.collect[List]()).map { articles =>
      Ok(views.html.articles(articles, activeSort))
    }.recover {
      case e =>
        e.printStackTrace()
        BadRequest(e.getMessage())
    }
  }

  def showCreationForm = Action { request =>
    implicit val messages = messagesApi.preferred(request)

    Ok(views.html.editArticle(None, Article.form, None))
  }

  def showEditForm(id: String) = Action.async { request =>
    // get the documents having this id (there will be 0 or 1 result)
    def futureArticle = collection.flatMap(
      _.find(Json.obj("_id" -> id)).one[Article])

    // ... so we get optionally the matching article, if any
    // let's use for-comprehensions to compose futures
    for {
      // get a future option of article
      maybeArticle <- futureArticle
      // if there is some article, return a future of result with the article and its attachments
      fs <- gridFS
      result <- maybeArticle.map { article =>
        // search for the matching attachments
        // find(...).toList returns a future list of documents
        // (here, a future list of ReadFileEntry)
        fs.find[JsObject, JSONReadFile](
          Json.obj("article" -> article.id.get)).collect[List]().map { files =>

          @inline def filesWithId = files.map { file => file.id -> file }
          implicit val messages = messagesApi.preferred(request)
          
          Ok(views.html.editArticle(Some(id),
            Article.form.fill(article), Some(filesWithId)))
        }
      }.getOrElse(Future.successful(NotFound))
    } yield result
  }

  def create = Action.async { implicit request =>
    implicit val messages = messagesApi.preferred(request)

    Article.form.bindFromRequest.fold(
      errors => Future.successful(
        Ok(views.html.editArticle(None, errors, None))),

      // if no error, then insert the article into the 'articles' collection
      article => collection.flatMap(_.insert(article.copy(
        id = article.id.orElse(Some(UUID.randomUUID().toString)),
        creationDate = Some(new DateTime()),
        updateDate = Some(new DateTime()))
      )).map(_ => Redirect(routes.Articles.index))
    )
  }

  def edit(id: String) = Action.async { implicit request =>
    implicit val messages = messagesApi.preferred(request)
    import reactivemongo.bson.BSONDateTime

    Article.form.bindFromRequest.fold(
      errors => Future.successful(
        Ok(views.html.editArticle(Some(id), errors, None))),

      article => {
        // create a modifier document, ie a document that contains the update operations to run onto the documents matching the query
        val modifier = Json.obj(
          // this modifier will set the fields
          // 'updateDate', 'title', 'content', and 'publisher'
          "$set" -> Json.obj(
            "updateDate" -> BSONDateTime(new DateTime().getMillis),
            "title" -> article.title,
            "content" -> article.content,
            "publisher" -> article.publisher))

        // ok, let's do the update
        collection.flatMap(_.update(Json.obj("_id" -> id), modifier).
          map { _ => Redirect(routes.Articles.index) })
      })
  }

  def delete(id: String) = Action.async {
    // let's collect all the attachments matching that match the article to delete
    (for {
      fs <- gridFS
      files <- fs.find[JsObject, JSONReadFile](
        Json.obj("article" -> id)).collect[List]()
      _ <- {
        // for each attachment, delete their chunks and then their file entry
        def deletions = files.map(fs.remove(_))

        Future.sequence(deletions)
      }
      coll <- collection
      _ <- {
        // now, the last operation: remove the article
        coll.remove(Json.obj("_id" -> id))
      }
    } yield Ok).recover { case _ => InternalServerError }
  }

  // save the uploaded file as an attachment of the article with the given id
  def saveAttachment(id: String) = {
    def fs = Await.result(gridFS, Duration("5s"))
    Action.async(gridFSBodyParser(fs)) { request =>
      // here is the future file!
      val futureFile = request.body.files.head.ref

      futureFile.onFailure {
        case err => err.printStackTrace()
      }

      // when the upload is complete, we add the article id to the file entry (in order to find the attachments of the article)
      val futureUpdate = for {
        file <- futureFile
        // here, the file is completely uploaded, so it is time to update the article
        updateResult <- fs.files.update(
          Json.obj("_id" -> file.id),
          Json.obj("$set" -> Json.obj("article" -> id)))
      } yield Redirect(routes.Articles.showEditForm(id))

      futureUpdate.recover {
        case e => InternalServerError(e.getMessage())
      }
    }
  }

  def getAttachment(id: String) = Action.async { request =>
    gridFS.flatMap { fs =>
      // find the matching attachment, if any, and streams it to the client
      val file = fs.find[JsObject, JSONReadFile](Json.obj("_id" -> id))

      request.getQueryString("inline") match {
        case Some("true") =>
          serve[JsString, JSONReadFile](fs)(file, CONTENT_DISPOSITION_INLINE)

        case _            => serve[JsString, JSONReadFile](fs)(file)
      }
    }
  }

  def removeAttachment(id: String) = Action.async {
    gridFS.flatMap(_.remove(Json toJson id).map(_ => Ok).
      recover { case _ => InternalServerError })
  }

  private def getSort(request: Request[_]): Option[JsObject] =
    request.queryString.get("sort").map { fields =>
      val sortBy = for {
        order <- fields.map { field =>
          if (field.startsWith("-"))
            field.drop(1) -> -1
          else field -> 1
        }
        if order._1 == "title" || order._1 == "publisher" || order._1 == "creationDate" || order._1 == "updateDate"
      } yield order._1 -> implicitly[Json.JsValueWrapper](Json.toJson(order._2))

      Json.obj(sortBy: _*)
    }

}
