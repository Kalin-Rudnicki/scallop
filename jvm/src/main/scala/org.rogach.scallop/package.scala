package org.rogach

import java.io.File
import java.net.{MalformedURLException, URL, URI, URISyntaxException}
import java.nio.file.{InvalidPathException,Path,Paths}

package object scallop extends DefaultConverters {
  implicit val fileConverter: ValueConverter[File] =
    singleArgConverter(new File(_))
  implicit val fileListConverter: ValueConverter[List[File]] =
    listArgConverter(new File(_))
  implicit val pathConverter = singleArgConverter[Path](Paths.get(_), {
    case e: InvalidPathException => Left("bad Path, %s" format e.getMessage)
  })
  implicit val pathListConverter: ValueConverter[List[Path]] =
    listArgConverter[Path](Paths.get(_))
  implicit val urlConverter = singleArgConverter(new URL(_), {
    case e: MalformedURLException => Left("bad URL, %s" format e.getMessage)
  })
  implicit val uriConverter = singleArgConverter(new URI(_), {
    case e: URISyntaxException => Left("bad URI, %s" format e.getMessage)
  })
}
