package org.rogach.scallop

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.rogach.scallop._
import org.rogach.scallop.exceptions._

class DefaultNames extends UsefulMatchers with CapturingTest with Matchers {

  test ("default for props") {
    object Conf extends ScallopConf(List("-D", "foo=bar,", "bar=baz,", "baz=bippy")) {
      val properties = props[String]()
      verify()
    }
    Conf.properties should equal (Map("foo"->"bar", "bar"->"baz", "baz"->"bippy"))
  }

  test ("default name for propsLong") {
    object Conf extends ScallopConf(List("--Props", "foo=bar", "bar=baz")) {
      val properties = propsLong[String]()
      verify()
    }
    Conf.properties should equal (Map("foo" -> "bar", "bar" -> "baz"))
  }

}
