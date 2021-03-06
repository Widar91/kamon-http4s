/*
 * =========================================================================================
 * Copyright © 2013-2018 the kamon project <http://kamon.io/>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */

package kamon.http4s

import java.net.URL
import java.util.concurrent.Executors

import cats.effect.IO
import kamon.http4s.middleware.server.KamonSupport
import kamon.trace.Span
import kamon.trace.Span.TagValue
import org.http4s.HttpService
import org.http4s.dsl.impl.Root
import org.http4s.dsl.io._
import org.http4s.server.Server
import org.http4s.server.blaze.BlazeBuilder
import org.scalatest.concurrent.Eventually
import org.scalatest.time.SpanSugar
import org.scalatest.{BeforeAndAfterAll, Matchers, OptionValues, WordSpec}

import scala.concurrent.ExecutionContext
import scala.io.Source


class ServerInstrumentationSpec extends WordSpec
  with Matchers
  with Eventually
  with SpanSugar
  with OptionValues
  with SpanReporter
  with BeforeAndAfterAll {

  val server: Server[IO] =
    BlazeBuilder[IO]
      .bindAny()
      .withExecutionContext(ExecutionContext.fromExecutor(Executors.newFixedThreadPool(2)))
      .mountService(KamonSupport(HttpService {
          case GET -> Root / "tracing" / "ok" =>  Ok("ok")
          case GET -> Root / "tracing" / "not-found"  => NotFound("not-found")
          case GET -> Root / "tracing" / "error"  => InternalServerError("This page will generate an error!")
        }
      ))
      .start
      .unsafeRunSync()

  private def get(path: String): String =
    Source
      .fromURL(new URL(s"http://127.0.0.1:${server.address.getPort}$path"))
      .getLines
      .mkString


  "The Server  instrumentation" should {
    "propagate the current context and respond to the ok action" in {
      get("/tracing/ok") should startWith("ok")

      eventually(timeout(2 seconds)) {
        val span = reporter.nextSpan().value
        val spanTags = stringTag(span) _

        span.operationName shouldBe "tracing.ok.get"
        spanTags("span.kind") shouldBe "server"
        spanTags("component") shouldBe "http4s.server"
        spanTags("http.method") shouldBe "GET"
        span.tags("http.status_code") shouldBe TagValue.Number(200)
      }
    }

    "propagate the current context and respond to the not-found action" in {
      intercept[Exception] {
        get("/tracing/not-found") should startWith("not-found")
      }

      eventually(timeout(2 seconds)) {
        val span = reporter.nextSpan().value
        val spanTags = stringTag(span) _

        span.operationName shouldBe "not-found"
        spanTags("span.kind") shouldBe "server"
        spanTags("component") shouldBe "http4s.server"
        spanTags("http.method") shouldBe "GET"
        span.tags("http.status_code") shouldBe TagValue.Number(404)
      }
    }

    "propagate the current context and respond to the error action" in {
      intercept[Exception] {
        get("/tracing/error") should startWith("error")
      }

      eventually(timeout(2 seconds)) {
        val span = reporter.nextSpan().value
        val spanTags = stringTag(span) _

        span.operationName shouldBe "tracing.error.get"
        spanTags("span.kind") shouldBe "server"
        spanTags("component") shouldBe "http4s.server"
        spanTags("http.method") shouldBe "GET"
        span.tags("error") shouldBe TagValue.True
        span.tags("http.status_code") shouldBe TagValue.Number(500)
      }
    }
  }

  def stringTag(span: Span.FinishedSpan)(tag: String): String = {
    span.tags(tag).asInstanceOf[TagValue.String].string
  }

  override protected def beforeAll(): Unit =
    start()

  override def afterAll: Unit = {
    stop()
    server.shutdownNow()
  }
}
