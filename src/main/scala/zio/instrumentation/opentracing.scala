package zio.instrumentation

import scalaz.zio._
import scalaz.zio.console._

/** Poor man's union types */
sealed trait TracingValue
object TracingValue {
  final case class StringTracingValue(value: String)   extends TracingValue
  final case class NumberTracingValue(value: Number)   extends TracingValue
  final case class BooleanTracingValue(value: Boolean) extends TracingValue

  implicit def tracingValueFromString(value: String): TracingValue         = TracingValue.StringTracingValue(value)
  implicit def tracingValueFromBoolean(value: Boolean): TracingValue       = TracingValue.BooleanTracingValue(value)
  implicit def tracingValueFromInt(value: Int): TracingValue               = TracingValue.NumberTracingValue(value)
  implicit def tracingValueFromDouble(value: Double): TracingValue         = TracingValue.NumberTracingValue(value)
  implicit def tracingValueFromBigDecimal(value: BigDecimal): TracingValue = TracingValue.NumberTracingValue(value)
}

trait Tracing extends Serializable {
  val tracing: Tracing.Service
}
object Tracing extends Serializable {
  type Headers = Map[String, String]

  trait Service {
    type Span

    def currentSpan: FiberLocal[Span]
    final def inAChildSpan[R <: Console, E, A](
      use: ZIO[R, E, A]
    )(operationName: String, tags: Seq[(String, TracingValue)]): ZIO[R, Any, A] = { // FIXME: Error is Any
      val acquire = for {
        parent <- currentSpan.get.get
        span   <- startChild(parent, operationName)
        _      <- ZIO.foreach(tags) { case (k, v) => setTag(span, k, v) }
      } yield (finish(span), currentSpan.locally(span)(use).fork)

      ZIO.bracket(acquire)(_._1)(_._2.flatMap(_.join)) // TODO: FiberLocal.local ?
    }

    /** Selected functionality from io.opentracing.{Tracer, Span} */
    def startSpan(operationName: String, upstreamSpan: Headers): IO[Throwable, Span]
    def export(span: Span): Headers
    def startChild(span: Span, operationName: String): IO[Throwable, Span]
    def finish(span: Span): UIO[Unit]
    def setTag(span: Span, key: String, value: TracingValue): IO[Throwable, Unit]
    def log(span: Span, fields: Seq[(String, TracingValue)]): IO[Throwable, Unit]
    def setBaggageItem(span: Span, key: String, value: String): IO[Throwable, Unit]
    def getBaggageItem(span: Span, key: String): IO[Throwable, Option[String]]
  }
}

class OpenTracing(tracer: io.opentracing.Tracer, currentFiberLocal: FiberLocal[io.opentracing.Span]) extends Tracing {
  import Tracing.Headers
  import io.opentracing.propagation._
  import scala.collection.JavaConverters._
  override val tracing: Tracing.Service = new Tracing.Service {
    override type Span = io.opentracing.Span

    override def currentSpan: FiberLocal[Span] = currentFiberLocal

    override def startSpan(operationName: String, upstreamSpan: Headers): IO[Throwable, Span] = {
      val upstream = tracer.extract(Format.Builtin.HTTP_HEADERS, new TextMapExtractAdapter(upstreamSpan.asJava))
      val span =
        tracer
          .buildSpan(operationName)
          .asChildOf(upstream)
      // TODO: Double check that the java code above doesn't side effect
      ZIO(span.start)
    }

    override def export(span: Span): Headers = {
      val carrier = new java.util.HashMap[String, String]() // Warning, mutability ahead!
      tracer.inject(span.context, Format.Builtin.HTTP_HEADERS, new TextMapInjectAdapter(carrier))
      carrier.asScala.toMap
    }

    override def startChild(span: Span, operationName: String): IO[Throwable, Span] =
      ZIO(tracer.buildSpan(operationName).asChildOf(span).start())
    override def finish(span: Span): UIO[Unit] = UIO(span.finish())
    override def setTag(span: Span, key: String, v: TracingValue): IO[Throwable, Unit] = v match {
      case TracingValue.StringTracingValue(value)  => ZIO(span.setTag(key, value)).unit
      case TracingValue.NumberTracingValue(value)  => ZIO(span.setTag(key, value)).unit
      case TracingValue.BooleanTracingValue(value) => ZIO(span.setTag(key, value)).unit
    }
    override def log(span: Span, fields: Seq[(String, TracingValue)]): IO[Throwable, Unit] = {
      val jFields = new java.util.HashMap[String, Any]()
      fields.foreach {
        case (key, TracingValue.StringTracingValue(value))  => jFields.put(key, value)
        case (key, TracingValue.NumberTracingValue(value))  => jFields.put(key, value)
        case (key, TracingValue.BooleanTracingValue(value)) => jFields.put(key, value)
      }
      ZIO(span.log(jFields)).unit
    }

    override def setBaggageItem(span: Span, key: String, value: String): IO[Throwable, Unit] =
      ZIO(span.setBaggageItem(key, value)).unit
    override def getBaggageItem(span: Span, key: String): IO[Throwable, Option[String]] =
      ZIO(Option(span.getBaggageItem(key)))
  }
}

object TracingZioApi {
  implicit class InstrumentedZIO[R <: Tracing with Console, E, A](self: ZIO[R, E, A]) {
    def instrumented(operationName: String, tags: (String, TracingValue)*): ZIO[R, Any, A] = // FIXME: Error is Any
      ZIO.access[Tracing](_.tracing).flatMap(_.inAChildSpan(self)(operationName, tags))
  }

  def log(fields: (String, TracingValue)*) = ZIO.access[Tracing] { r =>
    for {
      span <- r.tracing.currentSpan.get
      _    <- r.tracing.log(span.get, fields)
    } yield ()
  }

}

object MyApp extends App {
  import TracingZioApi._
  import io.jaegertracing.Configuration, Configuration.SamplerConfiguration, Configuration.ReporterConfiguration

  val tracerConf =
    new Configuration("example")
      .withSampler(SamplerConfiguration.fromEnv().withType("const").withParam(1))
      .withReporter(ReporterConfiguration.fromEnv().withLogSpans(true))

  def dirtyLog[A](a: A): A = { println(a); a }

  def run(args: List[String]) =
    for {
      currentSpan <- FiberLocal.make[io.opentracing.Span] // to be created by http middleware for every request
      tracer      <- UIO(tracerConf.getTracer)
      r           = new OpenTracing(tracer, currentSpan) with Console.Live
      rootSpan    <- UIO(tracer.buildSpan("REQUEST-ROOT").start())
      res         <- currentSpan.locally(rootSpan)(myAppLogic.fold(x => { dirtyLog(x); 1 }, _ => 0).provide(r))
      _           <- UIO(rootSpan.finish())
    } yield res

  val myAppLogic =
    for {
      _ <- doSomething(1).instrumented("parent1")
      _ <- doSomething(2).instrumented("parent2")
      _ <- doSomething(3).instrumented("parent3")
    } yield ()

  def doSomething(n: Int) =
    for {
      _ <- putStrLn(s"Child 1 of Parent $n").instrumented(s"child1-of-parent$n", "child" -> 1, "parent"  -> n)
      _ <- log("LEVEL" -> "DEBUG", 
               "n" -> n, 
               "isFoo" -> true)
      _ <- putStrLn(s"Child 2 of Parent $n").instrumented(s"child2-of-parent$n", "child" -> 2, "parent"  -> n)
    } yield ()
}
