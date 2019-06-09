/*
 * Copyright 2019 Tamer Abdulradi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import scalaz.zio._
import scalaz.zio.experimental
import zio.instrumentation._

object ExperimentalAutoInstrumentation extends App {
  import io.jaegertracing.Configuration, Configuration.SamplerConfiguration, Configuration.ReporterConfiguration

  val tracerConf =
    new Configuration("zio-opentracing-demo")
      .withSampler(SamplerConfiguration.fromEnv().withType("const").withParam(1))
      .withReporter(ReporterConfiguration.fromEnv().withLogSpans(true))

  // Fixme: Instantiation is a bit cumbersome
  def run(args: List[String]) =
    for {
      tracer     <- UIO(tracerConf.getTracer)
      rootSpan   <- UIO(tracer.buildSpan("REQUEST-ROOT").start())
      propagator <- Propagator.make[io.opentracing.Span](new OpenTracingBackend(tracer), rootSpan)
      r          = new Tracing(propagator) with console.Console.Live
      _          <- experimental.auto(myAppLogic).provide(r)
      _          <- UIO(rootSpan.finish())
    } yield 0

  val myAppLogic =
    for {
      _      <- doSomething(1)
      fiber1 <- doSomething(2).fork
      fiber2 <- doSomething(3).fork
      _      <- doSomething(4)
      _      <- fiber1.join
      _      <- fiber2.join
      _      <- doSomething(5)
    } yield ()

  def doSomething(n: Int) =
    for {
      _ <- console.putStrLn(s"Child 1 of Parent $n")
      _ <- console.putStrLn(s"Child 2 of Parent $n")
    } yield ()
}
