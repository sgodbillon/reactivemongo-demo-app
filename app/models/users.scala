package models

import org.asyncmongo.bson._
import org.asyncmongo.handlers._

import org.jboss.netty.buffer._

import play.api.data._
import play.api.data.Forms._
import play.api.data.format.Formats._
import play.api.data.validation.Constraints._


case class User(
  id: Option[BSONObjectID] = None,
  lastName: String,
  firstName: String,
  email: String,
  password: Option[String]
)

object User {
  val form = Form(
    mapping(
      "id" -> optional(of[String] verifying pattern(
        """[a-fA-F0-9]{24}""".r,
        "constraint.objectId",
        "error.email")),//play.api.data.Mapping,
      "lastName" -> nonEmptyText,
      "firstName" -> nonEmptyText,
      "email" -> email,
      "password" -> optional(text)
    ) { (id, lastName, firstName, email, password) =>
      User(
        id.map(new BSONObjectID(_)),
        lastName,
        firstName,
        email,
        password)
    } { user =>
      Some((user.id.map(_.stringify), user.lastName, user.firstName, user.email, user.password))
    }
  )

  object UserBSONWriter extends BSONWriter[User] {
    def write(user: User) = Bson(
        "lastName" -> BSONString(user.lastName),
        "firstName" -> BSONString(user.firstName),
        "email" -> BSONString(user.email)).makeBuffer
  }

  object UserBSONReader extends BSONReader[User] {
    def read(buffer: ChannelBuffer) = {
      val map = DefaultBSONHandlers.DefaultBSONReader.read(buffer).mapped
      User(
        map.get("_id").map(_.value match {
          case id :BSONObjectID => id
          case _ => throw new RuntimeException()
        }),
        map.get("lastName").map(_.value match {
          case BSONString(lastName) => lastName
          case _ => throw new RuntimeException()
        }).get,
        map.get("firstName").map(_.value match {
          case BSONString(firstName) => firstName
          case _ => throw new RuntimeException()
        }).get,
        map.get("email").map(_.value match {
          case BSONString(email) => email
          case _ => throw new RuntimeException()
        }).get,
        map.get("password").map(_.value match {
          case BSONString(password) => password
          case _ => throw new RuntimeException()
        }))
    }
  }
}