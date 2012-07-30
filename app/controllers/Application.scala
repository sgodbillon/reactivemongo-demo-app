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
import models._
import play.api.libs.concurrent.Promise

object Application extends Controller with MongoController {
  implicit val connection = MongoAsyncPlugin.connection
  val db = MongoAsyncPlugin.db

  def index = Action { request =>
    val sort = request.queryString.get("sort").flatMap { sort =>
      if(!sort.isEmpty) {
        Some(Bson(sort.head -> BSONInteger(1)))
      } else None
    }
    MongoPromiseResult {
      implicit val userReader = User.UserBSONReader
      val query = sort.map(sort => Bson("$orderby" -> sort.toDocument, "$query" -> Bson().toDocument)).getOrElse(Bson())
      println(query)
      val cursor = db("users").find(query)
      val list = collect[List, User](cursor)
      list.map { users =>
        Ok(views.html.index(users))
      }
    }
  }
  
  
  def createForm = userForm(None)
  
  def editForm(id: String) = userForm(Some(id))
  
  def userForm(maybeId: Option[String]) = Action {
    implicit val userReader = User.UserBSONReader
    maybeId.map { id =>
      MongoPromiseResult {
        val objectId = new BSONObjectID(id)
        val futureCursor = db("users").find(Bson("_id" -> objectId))
        val futureMaybeUser = collect[Set, User](futureCursor).map(_.headOption)
        futureMaybeUser.map { maybeUser =>
          maybeUser.map { user =>
            Ok(views.html.editUser(false, User.form.fill(user)))
          }.getOrElse(NotFound)
        }
      }
    }.getOrElse(Ok(views.html.editUser(true, User.form)))
  }
  
  def createUser = Action { implicit request =>
    User.form.bindFromRequest.fold(
      errors => Ok(views.html.editUser(false, errors)),
      user => MongoFutureResult {
        db("users").insert(user)(User.UserBSONWriter).map { _ =>
          Redirect(routes.Application.index())
        }
      }
    )
  }
  
  def editUser(id: String) = Action { implicit request =>
    implicit val userWriter = User.UserBSONWriter
    User.form.bindFromRequest.fold(
      errors => Ok(views.html.editUser(true, errors)),
      user => MongoFutureResult {
        val objectId = new BSONObjectID(id)
        db("users").update(Bson("_id" -> objectId), user.copy(id = Some(objectId))).map { _ =>
          Redirect(routes.Application.index())
        }
      }
    )
  }
  
  import play.api.data._
  import play.api.data.Forms._
  import play.api.data.validation.Constraints._

  val searchForm = Form(
    mapping(
      "name" -> text
    )(s => s)(s => Some(s))
  )
}