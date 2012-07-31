package controllers

import play.api._
import play.api.libs.iteratee._
import play.api.mvc._
import play.api.Play.current
import play.api.data.Form
import play.modules.mongodb.MongoAsyncPlugin
import org.asyncmongo.bson._
import org.asyncmongo.api._
import org.asyncmongo.handlers.DefaultBSONHandlers._
import org.asyncmongo.gridfs._
import models._
import play.api.libs.concurrent._
import org.joda.time._
import org.asyncmongo.protocol.commands.GetLastError
import org.asyncmongo.protocol.commands.FindAndModify
import org.asyncmongo.protocol.commands.Remove
import akka.dispatch.Future
import org.asyncmongo.actors.MongoDBSystem

object Articles extends Controller with MongoController {
  implicit val connection = MongoAsyncPlugin.connection
  
  val db = MongoAsyncPlugin.db
  val collection = db("articles")
  val gridFS = new GridFS(db, "attachments")
  
  def index = Action { implicit request =>
    MongoPromiseResult {
      implicit val reader = Article.ArticleBSONReader
      val query = Bson()
      val sort = getSort(request)
      if(sort.isDefined) {
        query += "$orderby" -> sort.get.toDocument
        query += "$query" -> Bson().toDocument
      }
      val found = collection.find(query)
      val activeSort = request.queryString.get("sort").flatMap(_.headOption).getOrElse("none")
      collect[List, Article](collection.find(query)).map { articles =>
        Ok(views.html.articles(articles, activeSort))
      }
    }
  }
  
  def showCreationForm = Action {
    Ok(views.html.editArticle(None, Article.form, None))
  }
  
  def showEditForm(id: String) = Action {
    implicit val reader = Article.ArticleBSONReader
    MongoPromiseResult {
      val objectId = new BSONObjectID(id)
      val futureCursor = collection.find(Bson("_id" -> objectId))
      val futureMaybeUser = collect[Set, Article](futureCursor).map(_.headOption)
      futureMaybeUser.flatMap { maybeArticle =>
        maybeArticle.map { article =>
          collect[List, ReadFileEntry](gridFS.find(Bson("article" -> article.id.get))).map { files =>
            val filesWithId = files.map { file =>
              file.id.asInstanceOf[BSONObjectID].stringify -> file
            }
            Ok(views.html.editArticle(Some(id), Article.form.fill(article), Some(filesWithId)))
          }
        }.getOrElse(Promise.pure(NotFound))
      }
    }
  }
  
  def create = Action { implicit request =>
    Article.form.bindFromRequest.fold(
      errors => Ok(views.html.editArticle(None, errors, None)),
      article => MongoFutureResult {
        collection.insert(article.copy(creationDate = Some(new DateTime()), updateDate = Some(new DateTime()))).map( _ =>
          Redirect(routes.Articles.index)
        )
      }
    )
  }
  
  def edit(id: String) = Action { implicit request =>
    Article.form.bindFromRequest.fold(
      errors => Ok(views.html.editArticle(Some(id), errors, None)),
      article => MongoFutureResult {
        val objectId = new BSONObjectID(id)
        val modifier = Bson(
          "$set" -> Bson(
            "updateDate" -> BSONDateTime(new DateTime().getMillis),
            "title" -> BSONString(article.title),
            "content" -> BSONString(article.content),
            "publisher" -> BSONString(article.publisher)).toDocument)
        collection.update(Bson("_id" -> objectId), modifier).map { _ =>
          Redirect(routes.Articles.index)
        }
      }
    )
  }
  
  def delete(id: String) = Action {
    implicit val ec = MongoConnection.system
    MongoPromiseResult {
      collect[List, ReadFileEntry](gridFS.find(Bson("article" -> new BSONObjectID(id)))).flatMap { files =>
        val cr = files.map { file =>
          gridFS.chunks.remove(Bson("files_id" -> file.id)).flatMap { h =>
            gridFS.files.remove(Bson("_id" -> file.id))
          }
        }
        new AkkaPromise(Future.sequence(cr))
      }.flatMap { _ =>
        new AkkaPromise(collection.remove(Bson("_id" -> new BSONObjectID(id))))
      }.map(_ => Ok).recover { case _ => InternalServerError}
    }
  }
  
  def saveAttachment(id: String) = Action(gridFSBodyParser(gridFS)) { request =>
    implicit val reader = Article.ArticleBSONReader
    val uploaded = collect[List, Article](collection.find(Bson("_id" -> new BSONObjectID(id)))).map(_.headOption)
    MongoPromiseResult {
      uploaded.filter(_.isDefined).flatMap { o =>
        val article = o.get
        val tt = Promise.sequence(request.body).flatMap { jj => 
          val fileId = jj.head.id
          new AkkaPromise(gridFS.files.update(Bson("_id" -> fileId), Bson("$set" -> Bson("article" -> article.id.get).toDocument)))
        }.map { _ =>
          Redirect(routes.Articles.showEditForm(id))
        }
        tt
      }
    }
  }
  
  def getAttachment(id: String) = Action {
    MongoPromiseResult {
      serve(gridFS.find(Bson("_id" -> new BSONObjectID(id))))
    }
  }
  
  def removeAttachment(id: String) = Action {
    MongoFutureResult {
      gridFS.files.remove(Bson("_id" -> new BSONObjectID(id))).flatMap { _ =>
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