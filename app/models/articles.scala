package models

import org.asyncmongo.bson._
import org.asyncmongo.handlers._

import org.jboss.netty.buffer._

import org.joda.time.DateTime

import play.api.data._
import play.api.data.Forms._
import play.api.data.format.Formats._
import play.api.data.validation.Constraints._

case class Article(
  id: Option[BSONObjectID],
  title: String,
  content: String,
  publisher: String,
  creationDate: Option[DateTime],
  updateDate: Option[DateTime]
)
// turn off your mind, relax, and flow downstream
// it is not dying...
object Article {
  implicit object ArticleBSONReader extends BSONReader[Article] {
    def read(buf: ChannelBuffer) :Article = {
      val mapped = DefaultBSONHandlers.DefaultBSONReader.read(buf).mapped
      Article(
        mapped.get("_id").flatMap(_.value match {
          case id: BSONObjectID => Some(id)
          case _ => None
        }),
        mapped.get("title").flatMap(_.value match {
          case BSONString(title) => Some(title)
          case _ => None
        }).get,
        mapped.get("content").flatMap(_.value match {
          case BSONString(content) => Some(content)
          case _ => None
        }).get,
        mapped.get("publisher").flatMap(_.value match {
          case BSONString(publisher) => Some(publisher)
          case _ => None
        }).get,
        mapped.get("creationDate").flatMap(_.value match {
          case BSONDateTime(time) => Some(new DateTime(time))
          case _ => None
        }),
        mapped.get("updateDate").flatMap(_.value match {
          case BSONDateTime(time) => Some(new DateTime(time))
          case _ => None
        }))
    }
  }
  implicit object ArticleBSONWriter extends BSONWriter[Article] {
    def write(article: Article) = {
      val bson = Bson(
        "_id" -> article.id.getOrElse(BSONObjectID.generate),
        "title" -> BSONString(article.title),
        "content" -> BSONString(article.content),
        "publisher" -> BSONString(article.publisher))
      if(article.creationDate.isDefined)
        bson += "creationDate" -> BSONDateTime(article.creationDate.get.getMillis)
      if(article.updateDate.isDefined)
        bson += "updateDate" -> BSONDateTime(article.updateDate.get.getMillis)
      bson.makeBuffer
    }
  }
  val form = Form(
    mapping(
      "id" -> optional(of[String] verifying pattern(
        """[a-fA-F0-9]{24}""".r,
        "constraint.objectId",
        "error.objectId")),
      "title" -> nonEmptyText,
      "content" -> text,
      "publisher" -> nonEmptyText,
      "creationDate" -> optional(of[Long]),
      "updateDate" -> optional(of[Long])
    ) { (id, title, content, publisher, creationDate, updateDate) =>
      Article(
        id.map(new BSONObjectID(_)),
        title,
        content,
        publisher,
        creationDate.map(new DateTime(_)),
        updateDate.map(new DateTime(_)))
    } { article =>
      Some(
        (article.id.map(_.stringify),
        article.title,
        article.content,
        article.publisher,
        article.creationDate.map(_.getMillis),
        article.updateDate.map(_.getMillis)))
    })
}