package controllers

import javax.inject.Inject

import org.joda.time.DateTime

import scala.concurrent.Future

import play.api.Logger
import play.api.Play.current
import play.api.i18n.{ I18nSupport, MessagesApi }
import play.api.mvc.{ Action, Controller, Request }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ Json, JsObject, JsString }

import reactivemongo.api.gridfs.{ GridFS, ReadFile }

import play.modules.reactivemongo.{
  MongoController, ReactiveMongoApi, ReactiveMongoComponents
}

import play.modules.reactivemongo.json._
import play.modules.reactivemongo.json.collection._

import models.Article, Article._

class Articles @Inject() (
  val messagesApi: MessagesApi,
  val reactiveMongoApi: ReactiveMongoApi)
    extends Controller with MongoController with ReactiveMongoComponents {

  import java.util.UUID
  import MongoController.readFileReads

  type JSONReadFile = ReadFile[JSONSerializationPack.type, JsString]

  // get the collection 'articles'
  val collection = db[JSONCollection]("articles")

  // a GridFS store named 'attachments'
  //val gridFS = GridFS(db, "attachments")
  private val gridFS = reactiveMongoApi.gridFS

  // let's build an index on our gridfs chunks collection if none
  gridFS.ensureIndex().onComplete {
    case index =>
      Logger.info(s"Checked index, result is $index")
  }

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
    val found = collection.find(query).cursor[Article]()
    // build (asynchronously) a list containing all the articles
    found.collect[List]().map { articles =>
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
    val futureArticle = collection.find(Json.obj("_id" -> id)).one[Article]
    // ... so we get optionally the matching article, if any
    // let's use for-comprehensions to compose futures (see http://doc.akka.io/docs/akka/2.0.3/scala/futures.html#For_Comprehensions for more information)
    for {
      // get a future option of article
      maybeArticle <- futureArticle
      // if there is some article, return a future of result with the article and its attachments
      result <- maybeArticle.map { article =>
        // search for the matching attachments
        // find(...).toList returns a future list of documents (here, a future list of ReadFileEntry)
        gridFS.find[JsObject, JSONReadFile](
          Json.obj("article" -> article.id.get)).collect[List]().map { files =>
          val filesWithId = files.map { file =>
            file.id -> file
          }

          implicit val messages = messagesApi.preferred(request)
          
          Ok(views.html.editArticle(Some(id),
            Article.form.fill(article), Some(filesWithId)))
        }
      }.getOrElse(Future(NotFound))
    } yield result
  }

  def create = Action.async { implicit request =>
    implicit val messages = messagesApi.preferred(request)

    Article.form.bindFromRequest.fold(
      errors => Future.successful(
        Ok(views.html.editArticle(None, errors, None))),

      // if no error, then insert the article into the 'articles' collection
      article => collection.insert(article.copy(
        id = article.id.orElse(Some(UUID.randomUUID().toString)),
        creationDate = Some(new DateTime()),
        updateDate = Some(new DateTime()))
      ).map(_ => Redirect(routes.Articles.index))
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
        collection.update(Json.obj("_id" -> id), modifier).
          map { _ => Redirect(routes.Articles.index) }
      })
  }

  def delete(id: String) = Action.async {
    // let's collect all the attachments matching that match the article to delete
    gridFS.find[JsObject, JSONReadFile](Json.obj("article" -> id)).
      collect[List]().flatMap { files =>
        // for each attachment, delete their chunks and then their file entry
        val deletions = files.map(gridFS.remove(_))

        Future.sequence(deletions)
      }.flatMap { _ =>
        // now, the last operation: remove the article
        collection.remove(Json.obj("_id" -> id))
      }.map(_ => Ok).recover { case _ => InternalServerError }
  }

  // save the uploaded file as an attachment of the article with the given id
  def saveAttachment(id: String) =
    Action.async(gridFSBodyParser(gridFS)) { request =>
      // here is the future file!
      val futureFile = request.body.files.head.ref

      futureFile.onFailure {
        case err => err.printStackTrace()
      }

      // when the upload is complete, we add the article id to the file entry (in order to find the attachments of the article)
      val futureUpdate = for {
        file <- { println("_0"); futureFile }
        // here, the file is completely uploaded, so it is time to update the article
        updateResult <- {
          println("_1")
          gridFS.files.update(
            Json.obj("_id" -> file.id),
            Json.obj("$set" -> Json.obj("article" -> id)))
        }
      } yield updateResult

      futureUpdate.map { _ =>
        Redirect(routes.Articles.showEditForm(id))
      }.recover {
        case e => InternalServerError(e.getMessage())
      }
    }

  def getAttachment(id: String) = Action.async { request =>
    // find the matching attachment, if any, and streams it to the client
    val file = gridFS.find[JsObject, JSONReadFile](Json.obj("_id" -> id))

    request.getQueryString("inline") match {
      case Some("true") =>
        serve[JsString, JSONReadFile](gridFS)(file, CONTENT_DISPOSITION_INLINE)

      case _            => serve[JsString, JSONReadFile](gridFS)(file)
    }
  }

  def removeAttachment(id: String) = Action.async {
    gridFS.remove(Json toJson id).map(_ => Ok).
      recover { case _ => InternalServerError }
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
