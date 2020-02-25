package org.rogach.scallop

import org.rogach.scallop.exceptions._

import org.scalatest.FunSuite

import scala.concurrent.duration._

class DurationConverterTest extends FunSuite with UsefulMatchers {
  throwError.value = true

  test("convert to Duration") {
    def getcf(args: Seq[String]) = new ScallopConf(args) {
      val foo = opt[Duration]()
      verify()
    }

    getcf(List("-f", "1 minute")).foo.toOption ==== Some(1.minute)
    getcf(List("-f", "Inf")).foo.toOption ==== Some(Duration.Inf)
    getcf(List("-f", "MinusInf")).foo.toOption ==== Some(Duration.MinusInf)

    expectException(WrongOptionFormat("foo", "bar", "wrong arguments format")) {
      getcf(List("-f", "bar")).foo.toOption ==== Some(Duration.MinusInf)
    }
  }
}

class FiniteDurationConverterTest extends FunSuite with UsefulMatchers {
  throwError.value = true

  test("convert to Duration") {
    def getcf(args: Seq[String]) = new ScallopConf(args) {
      val foo = opt[FiniteDuration]()
      verify()
    }

    getcf(List("-f", "1 minute")).foo.toOption ==== Some(1.minute)

    expectException(WrongOptionFormat("foo", "Inf", "wrong arguments format")) {
      getcf(List("-f", "Inf")).foo.toOption ==== Some(Duration.Inf)
    }

    expectException(WrongOptionFormat("foo", "bar", "wrong arguments format")) {
      getcf(List("-f", "bar")).foo.toOption ==== Some(Duration.MinusInf)
    }
  }
}
