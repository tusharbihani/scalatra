package org.scalatra
package commands

import org.specs2.mutable.Specification
import json.JsonSupport
import scalaz._
import Scalaz._
import javax.servlet.http.HttpServletRequest
import org.specs2.mock.Mockito
import util.MultiMap
import java.util
import org.scalatra.util

//import org.scalatra.validation.ValidationSupport

trait BindingTemplate { self: Command with TypeConverterFactories =>


  val upperCaseName: Field[String] = bind[String]("name").transform(_.toUpperCase)

  val lowerCaseSurname: Field[String] = asString("surname").transform(_.toLowerCase)

  val age: Field[Int] = asType[Int]("age") // explicit

  val cap: Field[Int] = "cap" // implicit

}

trait WithBinding extends Command with TypeConverterFactories with BindingTemplate {


  val a = upperCaseName

  val lower = lowerCaseSurname
}

class WithBindingFromParams extends WithBinding

class MixAndMatchCommand extends ParamsOnlyCommand {
  import ValueSource._
  val name: Field[String] = asString("name").notBlank
  val age: Field[Int] = "age"
  val token: Field[String] = (
      asString("API-TOKEN").notBlank
        sourcedFrom Header 
        description "The API token for this request"
        notes "Invalid data kills kittens"
        allowableValues "123")
  val skip: Field[Int] = asInt("skip").sourcedFrom(Query).description("The offset for this collection index")
  val limit: Field[Int] = asType[Int]("limit").sourcedFrom(Query).withDefaultValue(20).description("the max number of items to return")
}

class CommandWithConfirmationValidation extends ParamsOnlyCommand {
  val name: Field[String] = asString("name").notBlank
  val passwordConfirmation: Field[String] = asString("passwordConfirmation").notBlank
  val password: Field[String] = asString("password").notBlank.validForConfirmation(passwordConfirmation)
}


class CommandSpec extends Specification {

  import org.scalatra.util.ParamsValueReaderProperties._
  import BindingSyntax._
//  implicit val formats: Formats = DefaultFormats
  "The 'Command' trait" should {

    "bind and register a 'FieldDescriptor[T]' instance" in {
      val form = new WithBindingFromParams
      form.a must_== form.upperCaseName
    }

    "have unprocessed binding values set to 'None'" in {
      val form = new WithBindingFromParams
      form.a.validation must_== "".success
      form.lower.validation must_== "".success
    }

    "bindTo 'params' Map and bind matching values to specific keys" in {
      val form = new WithBindingFromParams
      val params = Map("name" -> "John", "surname" -> "Doe")
      form.bindTo(params)
      form.a.validation must_== params("name").toUpperCase.success
      form.lower.validation must_== params("surname").toLowerCase.success
    }
    
    "bindTo with values from all kinds of different sources and bind matching values to specific keys" in {
      val form = new MixAndMatchCommand
      val params = Map("name" -> "John", "age" -> "45", "limit" -> "30", "skip" -> "20")
      val multi = MultiMap(params map { case (k, v) => k -> Seq(v) })
      val hdrs = Map("API-TOKEN" -> "123")
      form.bindTo(params, multi, hdrs)
      form.name.value must beSome("John")
      form.age.value must beSome(45)
      form.limit.value must beSome(30)
      form.skip.value must beSome(20)
      form.token.value must beSome("123")
    }

    "bindTo 'params' with a confirmation" in {
      val form = new CommandWithConfirmationValidation
      form.bindTo(Map("name" -> "blah", "password" -> "blah123", "passwordConfirmation" -> "blah123"))
      form.isValid must beTrue
    }

    "provide pluggable actions processed 'BEFORE' binding " in {
      import System._

      trait PreBindAction extends WithBinding {

        var timestamp: Long = _

        beforeBinding {
          a.original must beNone
          timestamp = currentTimeMillis()-1
        }


      }

      val form = new WithBindingFromParams with PreBindAction
      val params = Map("name" -> "John", "surname" -> "Doe")

      form.timestamp must_== 0L

      val bound = form.bindTo(params)

      bound.timestamp must be_<(currentTimeMillis())
      bound.a.validation must_== params("name").toUpperCase.success
      bound.a.original must_== params.get("name")
    }

    "provide pluggable actions processed 'AFTER' binding " in {

      trait AfterBindAction extends WithBinding {

        private var _fullname: String = _

        def fullName: Option[String] = Option {
          _fullname
        }

        afterBinding {
          _fullname = a.validation.toOption.get + " " + lower.validation.toOption.get
        }
      }

      val params = Map("name" -> "John", "surname" -> "Doe")
      val form = new WithBindingFromParams with AfterBindAction

      form.fullName must beNone

      form.bindTo(params)

      form.fullName must beSome[String]
      form.fullName.get must_== params("name").toUpperCase + " " + params("surname").toLowerCase
    }
  }
}

import org.scalatra.test.specs2._


class CommandSample extends ParamsOnlyCommand {
  var bound = false

  afterBinding {
    bound = true
  }
}

class CommandSupportSpec extends Specification with Mockito {

  class ScalatraPage extends ScalatraFilter with ParamsOnlyCommandSupport
//  implicit val formats: Formats = DefaultFormats
  "The CommandSupport trait" should {

    "provide a convention for request keys with commandRequestKey[T]" in {

      val page = new ScalatraPage
      val key = page.commandRequestKey[CommandSample]
      key must_== "_command_" + classOf[CommandSample].getName

    }

    "look into request for existent command objects with commandOption[T]" in {

      import collection.mutable._

      val mockRequest: Map[String, Any] = Map.empty

      val page = new ScalatraPage {
        override private[commands] def requestProxy = mockRequest
      }

      page.commandOption[CommandSample] must beNone

      val instance = new CommandSample
      val key = page.commandRequestKey[CommandSample]
      mockRequest(key) = instance

      page.commandOption[CommandSample] must beSome[CommandSample]
      page.commandOption[CommandSample].get must_== instance
    }

    "create, bind and store in request new commands with command[T]" in {

      import org.scalatra.servlet.ServletApiImplicits._
      import collection.mutable._

      val req = smartMock[HttpServletRequest]
      val mockRequest: Map[String, Any] = Map.empty

      val page = new ScalatraPage {


        /**
         * The current multiparams.  Multiparams are a result of merging the
         * standard request params (query string or post params) with the route
         * parameters extracted from the route matchers of the current route.
         * The default value for an unknown param is the empty sequence.  Invalid
         * outside `handle`.
         */
        override def multiParams: _root_.org.scalatra.MultiParams = MultiMap()

        /**
         * The currently scoped request.  Valid only inside the `handle` method.
         */
        override implicit def request = req

        override private[commands] def requestProxy = mockRequest
      }

      val command = page.command[CommandSample]

      val key = page.commandRequestKey[CommandSample]
      mockRequest(key).asInstanceOf[AnyRef] must beTheSameAs(command)

      command.bound must beTrue
    }
  }

  "provide a RouteMatcher that match valid commands with ifValid[T]" in {

    class ValidatedCommand extends CommandSample

    val page = new ScalatraPage

    val matcher:RouteMatcher = page.ifValid[ValidatedCommand]

    matcher must not(beNull[RouteMatcher])
  }
}


