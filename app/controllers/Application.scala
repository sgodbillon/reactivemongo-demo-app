package controllers

import models._
import org.asyncmongo.api._
import org.asyncmongo.bson._
import org.asyncmongo.gridfs._
import org.asyncmongo.handlers.DefaultBSONHandlers._
import org.joda.time._
import play.api._
import play.api.mvc._
import play.api.Play.current
import play.modules.mongodb._
import scala.concurrent.{ExecutionContext, Future}

object Articles extends Controller with MongoController {
  implicit val connection = MongoAsyncPlugin.connection
  implicit val ec: ExecutionContext = ExecutionContext.Implicits.global

  val db = MongoAsyncPlugin.db
  val collection = db("articles")
  // a GridFS store named 'attachments'
  val gridFS = new GridFS(db, "attachments")

  // list all articles and sort them
  def index = Action { implicit request =>
    MongoAsyncResult {
      implicit val reader = Article.ArticleBSONReader
      // empty query to match all the documents
      val query = Bson()
      val sort = getSort(request)
      if(sort.isDefined) {
        // build a selection document with an empty query and a sort subdocument ('$orderby')
        query += "$orderby" -> sort.get.toDocument
        query += "$query" -> Bson().toDocument
      }
      val activeSort = request.queryString.get("sort").flatMap(_.headOption).getOrElse("none")
      // the future cursor of documents
      val found = collection.find(query)
      // build (asynchronously) a list containing all the articles
      found.toList.map { articles =>
        Ok(views.html.articles(articles, activeSort))
      }
    }
  }

  def showCreationForm = Action {
    Ok(views.html.editArticle(None, Article.form, None))
  }

  def showEditForm(id: String) = Action {
    implicit val reader = Article.ArticleBSONReader
    MongoAsyncResult {
      val objectId = new BSONObjectID(id)
      // get the documents having this id (there will be 0 or 1 result)
      val cursor = collection.find(Bson("_id" -> objectId))
      // ... so we get optionally the matching article, if any
      val futureMaybeUser = cursor.headOption
      futureMaybeUser.flatMap { maybeArticle =>
        // if there is an article matching the id, get its attachments too
        maybeArticle.map { article =>
          // build (asynchronously) a list containing all the attachments if the article
          gridFS.find(Bson("article" -> article.id.get)).toList.map { files =>
            val filesWithId = files.map { file =>
              file.id.asInstanceOf[BSONObjectID].stringify -> file
            }
            Ok(views.html.editArticle(Some(id), Article.form.fill(article), Some(filesWithId)))
          }
        }.getOrElse(Future(NotFound))
      }
    }
  }

  def create = Action { implicit request =>
    Article.form.bindFromRequest.fold(
      errors => Ok(views.html.editArticle(None, errors, None)),
      // if no error, then insert the article into the 'articles' collection
      article => MongoAsyncResult {
        collection.insert(article.copy(creationDate = Some(new DateTime()), updateDate = Some(new DateTime()))).map( _ =>
          Redirect(routes.Articles.index)
        )
      }
    )
  }

  def edit(id: String) = Action { implicit request =>
    Article.form.bindFromRequest.fold(
      errors => Ok(views.html.editArticle(Some(id), errors, None)),
      article => MongoAsyncResult {
        val objectId = new BSONObjectID(id)
        // create a modifier document, ie a document that contains the update operations to run onto the documents matching the query
        val modifier = Bson(
          // this modifier will set the fields 'updateDate', 'title', 'content', and 'publisher'
          "$set" -> Bson(
            "updateDate" -> BSONDateTime(new DateTime().getMillis),
            "title" -> BSONString(article.title),
            "content" -> BSONString(article.content),
            "publisher" -> BSONString(article.publisher)).toDocument)
        // ok, let's do the update
        collection.update(Bson("_id" -> objectId), modifier).map { _ =>
          Redirect(routes.Articles.index)
        }
      }
    )
  }

  def delete(id: String) = Action {
    MongoAsyncResult {
      // let's collect all the attachments matching that match the article to delete
      gridFS.find(Bson("article" -> new BSONObjectID(id))).toList.flatMap { files =>
        // for each attachment, delete their chunks and then their file entry
        val deletions = files.map { file =>
          // step 1: remove the chunks of the file
          gridFS.chunks.remove(Bson("files_id" -> file.id)).flatMap { _ =>
            // step 2: remove the file entry
            gridFS.files.remove(Bson("_id" -> file.id))
          }
        }
        Future.sequence(deletions)
      }.flatMap { _ =>
        // now, the last operation: remove the article
        collection.remove(Bson("_id" -> new BSONObjectID(id)))
      }.map(_ => Ok).recover { case _ => InternalServerError}
    }
  }

  // save the uploaded file as an attachment of the article with the given id
  def saveAttachment(id: String) = Action(gridFSBodyParser(gridFS)) { request =>
    // the reader that allows the 'find' method to return a future Cursor[Article]
    implicit val reader = Article.ArticleBSONReader
    // first, get the attachment matching the given id, and get the first result (if any)
    val cursor = collection.find(Bson("_id" -> new BSONObjectID(id)))
    val uploaded = cursor.headOption
    MongoAsyncResult {
      // we filter the future to get it successful only if there is a matching Article
      uploaded.filter(_.isDefined).flatMap { articleOption =>
        // ... so we're sure that we eventually get an article here
        val article = articleOption.get
        // wait (non-blocking) for the upload to finish. (This example does not handle multiple file uploads)
        val sequenceOfFutures = request.body.files.map(_.ref)

        Future.sequence(sequenceOfFutures).flatMap { putResults =>
          // we get the putResults, resulting of the upload of the attachment into the GridFS store
          putResults.headOption.map { result =>
            // and now we add the article id to the file entry (in order to find the attachments of an article)
            gridFS.files.update(Bson("_id" -> result.id), Bson("$set" -> Bson("article" -> article.id.get).toDocument)).map {
              case _ => Redirect(routes.Articles.showEditForm(id))
            }
          }.getOrElse(Future(BadRequest))
        }
      }
    }
  }

  def getAttachment(id: String) = Action {
    MongoAsyncResult {
      // find the matching attachment, if any, and streams it to the client
      serve(gridFS.find(Bson("_id" -> new BSONObjectID(id))))
    }
  }

  def removeAttachment(id: String) = Action {
    MongoAsyncResult {
      // first, remove the file entry matching this id
      gridFS.files.remove(Bson("_id" -> new BSONObjectID(id))).flatMap { _ =>
        // then remove the chunks of this file
        gridFS.chunks.remove(Bson("files_id" -> new BSONObjectID(id))).map { _ =>
          Ok
        }
      }.recover { case _ => InternalServerError }
    }
  }

  private def getSort(request: Request[_]) = {
    request.queryString.get("sort").map { fields =>
      val orderBy = Bson()
      for(field <- fields) {
        val order = if(field.startsWith("-"))
          field.drop(1) -> -1
        else field -> 1

        if(order._1 == "title" || order._1 == "publisher" || order._1 == "creationDate" || order._1 == "updateDate")
          orderBy += order._1 -> BSONInteger(order._2)
      }
      orderBy
    }
  }
}