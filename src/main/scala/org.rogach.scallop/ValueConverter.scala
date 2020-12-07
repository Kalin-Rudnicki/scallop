package org.rogach.scallop

import scala.util.Try

/** Converter from list of plain strings to something meaningful. */
trait ValueConverter[A] { parent =>

  /** Takes a list of arguments to all option invocations:
    * for example, "-a 1 2 -a 3 4 5" would produce List(("a",List(1,2)),("a",List(3,4,5))).
    * <ul>
    * <li> parse returns Left, if there was an error while parsing. </li>
    * <li> if no option was found, it returns Right(None). </li>
    * <li> if option was found, it returns Right(...). </li>
    * </ul>
    */
  def parse(s: List[(String, List[String])]): Either[String, Option[A]]

  private val parseCache = new scala.collection.mutable.HashMap[List[(String, List[String])], Either[String, Option[A]]]()
  def parseCached(s: List[(String, List[String])]): Either[String, Option[A]] = {
    parseCache.get(s) match {
      case Some(v) => v
      case None =>
        val v = parse(s)
        parseCache.put(s, v)
        v
    }
  }

  /** Type of parsed argument list. */
  val argType: ArgType.V

  /** Transformation of argument name to argument definition in help. */
  def argFormat(name: String): String = argType.fn(name)

  /** Maps the converter to another value:
    *
    * {{{
    * intConverter.map(2 +) // and you get a "biased converter"
    * }}}
    */
  def map[B](fn: A => B) = new ValueConverter[B] { child =>
    def parse(s: List[(String, List[String])]) =
      parent.parse(s) match {
        case Right(parseResult) =>
          Try(Right(parseResult.map(fn)))
            .recover({ case e: Exception => Left(e.toString) })
            .get
        case Left(msg) => Left(msg)
      }
    val argType = parent.argType
  }

  def flatMap[B](fn: A => Either[String, Option[B]]) =
    new ValueConverter[B] { child =>
      def parse(s: List[(String, List[String])]) =
        parent.parse(s) match {
          case Right(Some(parseResult)) =>
            Try(fn(parseResult))
              .recover({ case e: Exception => Left(e.toString) })
              .get
          case Right(None) => Right(None)
          case Left(msg) => Left(msg)
        }
      val argType = parent.argType
    }

}
