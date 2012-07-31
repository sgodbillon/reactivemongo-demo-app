# MongoDB with MongoAsync : a Sample App

This is a sample app to show in a few lines of code how MongoDB can be used to build a simple (yet full featured) web application.

This example use the following:
* MongoDB (*yeah, no kidding*)
* MongoAsync, a non-blocking and asynchronous Scala driver for MongoDB
* Play 2.1 as a web framework
* Play MongoAsync module

This application manages articles. An article has a title, a text content and a publisher. The articles can be updated and sorted by title, publisher, creation/update date, etc. One or more attachments can be uploaded and bound to an article (like an image, a pdf, an archive...). All the classic CRUD operations are implemented.

To sum up, this sample covers the following features of MongoDB:
* Simple queries
* Sorting the results of a query
* Update
* Delete
* GridFS a storage engine for the attachments

The following features of MongoAsync driver are covered:
* (Non-blocking) queries, updates, deletes
* (Non-blocking) GridFS storage
* Streaming files from and into GridFS
