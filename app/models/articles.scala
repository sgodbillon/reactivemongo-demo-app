package models

import org.jboss.netty.buffer._
import org.joda.time.DateTime
import play.api.data._
import play.api.data.Forms._
import play.api.data.format.Formats._
import play.api.data.validation.Constraints._

import reactivemongo.bson._
import reactivemongo.bson.DefaultBSONHandlers._
import reactivemongo.bson.handlers._

case class Article(
  id: Option[BSONObjectID],
  title: String,
  content: String,
  publisher: String,
  creationDate: Option[DateTime],
  updateDate: Option[DateTime]
)
// Turn off your mind, relax, and float downstream
// It is not dying...
object Article {
  implicit object ArticleBSONReader extends BSONDocumentDeserializer[Article] {
    def read(doc: BSONDocument) :Article = {
      Article(
        doc.getAs[BSONObjectID]("_id"),
        doc.getAs[BSONString]("title").get.value,
        doc.getAs[BSONString]("content").get.value,
        doc.getAs[BSONString]("publisher").get.value,
        doc.getAs[BSONDateTime]("creationDate").map(dt => new DateTime(dt.value)),
        doc.getAs[BSONDateTime]("updateDate").map(dt => new DateTime(dt.value)))
    }
  }
  implicit object ArticleBSONWriter extends BSONDocumentSerializer[Article] {
    def write(article: Article) = {
      BSONDocument(
        "_id" -> article.id.getOrElse(BSONObjectID.generate),
        "title" -> BSONString(article.title),
        "content" -> BSONString(article.content),
        "publisher" -> BSONString(article.publisher),
        "creationDate" -> article.creationDate.map(date => BSONDateTime(date.getMillis)),
        "updateDate" -> article.updateDate.map(date => BSONDateTime(date.getMillis)))
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