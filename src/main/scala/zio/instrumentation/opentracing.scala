package scalaz.zio
// package instrumentation

// import scalaz.zio._
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

/** Pure version of opentracing Tracer and Span operations */
object Tracer {
  type Headers = Map[String, String]
}
trait Tracer[Span] {
  import Tracer.Headers
  def startSpan(operationName: String, upstreamSpan: Headers): IO[Throwable, Span]
  def export(span: Span): Headers
  def startChild(span: Span, operationName: String): IO[Throwable, Span]
  def finish(span: Span): UIO[Unit]
  def setTag(span: Span, key: String, value: TracingValue): IO[Throwable, Unit]
  def log(span: Span, fields: Seq[(String, TracingValue)]): IO[Throwable, Unit]
  def setBaggageItem(span: Span, key: String, value: String): IO[Throwable, Unit]
  def getBaggageItem(span: Span, key: String): IO[Throwable, Option[String]]
}

class OpenTracingTracer(tracer: io.opentracing.Tracer) extends Tracer[io.opentracing.Span] {
  import Tracer.Headers
  import io.opentracing.propagation._
  import io.opentracing.Span
  import scala.collection.JavaConverters._

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

/** Deals with current span propagation */
trait Tracing extends Serializable {
  def tracing: Tracing.Service
}
object Tracing extends Serializable {
  trait Service {
    def inAChildSpan[R <: Console, E, A](
      use: ZIO[R, E, A]
    )(operationName: String, tags: Seq[(String, TracingValue)]): ZIO[R, Any, A]

    def log(fields: (String, TracingValue)*): IO[Any, Unit]
  }

  class Live[Span](tracer: Tracer[Span], val currentSpan: FiberRef[Span]) extends Tracing {
    override val tracing: Tracing.Service = new Tracing.Service {
      override def inAChildSpan[R <: Console, E, A](
        use: ZIO[R, E, A]
      )(operationName: String, tags: Seq[(String, TracingValue)]): ZIO[R, Any, A] = { // FIXME: Error is Any
        val acquire = for {
          parent <- currentSpan.get
          span   <- tracer.startChild(parent, operationName)
          _      <- ZIO.foreach(tags) { case (k, v) => tracer.setTag(span, k, v) }
        } yield (tracer.finish(span), currentSpan.locally(span)(use).fork)

        ZIO.bracket(acquire)(_._1)(_._2.flatMap(_.join))
      }

      override def log(fields: (String, TracingValue)*) =
        currentSpan.get.flatMap(tracer.log(_, fields))
    }
  }
}

object TracingZioApi {
  implicit class InstrumentedZIO[R <: Tracing with Console, E, A](self: ZIO[R, E, A]) {
    def instrumented(operationName: String, tags: (String, TracingValue)*): ZIO[R, Any, A] = // FIXME: Error is Any
      ZIO.access[Tracing](_.tracing).flatMap(_.inAChildSpan(self)(operationName, tags))

    // TODO: Try to infer operationName from stacktrace
    def autoInstrument[X1]: ZIO[R, Any, A] = self.tag match {
      case ZIO.Tags.FlatMap =>
        val flatMap = self.asInstanceOf[ZIO.FlatMap[R, E, X1, A]]
        new ZIO.FlatMap(flatMap.zio.autoInstrument, flatMap.k).instrumented("FlatMap")
      case ZIO.Tags.Succeed =>
        self.instrumented("Succeed")
      case ZIO.Tags.EffectTotal =>
        self.instrumented("EffectTotal")
      case ZIO.Tags.Fail            => ???
      case ZIO.Tags.Fold            => ???
      case ZIO.Tags.InterruptStatus => ???
      case ZIO.Tags.CheckInterrupt  => ???
      case ZIO.Tags.EffectPartial   => ???
      case ZIO.Tags.EffectAsync     => ???
      case ZIO.Tags.Fork            => ???
      case ZIO.Tags.SuperviseStatus => ???
      case ZIO.Tags.Descriptor      => ???
      case ZIO.Tags.Lock            => ???
      case ZIO.Tags.Yield           => ???
      case ZIO.Tags.Access =>
        val read = self.asInstanceOf[ZIO.Read[R, E, A]]
        new ZIO.Read((r: R) => read.k(r).autoInstrument).instrumented("Read")
      case ZIO.Tags.Provide        => ???
      case ZIO.Tags.SuspendWith    => ???
      case ZIO.Tags.FiberRefNew    => ???
      case ZIO.Tags.FiberRefModify => ???
      case ZIO.Tags.Trace          => ???
      case ZIO.Tags.TracingStatus  => ???
      case ZIO.Tags.CheckTracing   => ???
    }
  }

  def log(fields: (String, TracingValue)*) = ZIO.access[Tracing](_.tracing.log(fields: _*))
}

object MyApp extends App {
  import TracingZioApi._
  import io.jaegertracing.Configuration, Configuration.SamplerConfiguration, Configuration.ReporterConfiguration

  val tracerConf =
    new Configuration("example")
      .withSampler(SamplerConfiguration.fromEnv().withType("const").withParam(1))
      .withReporter(ReporterConfiguration.fromEnv().withLogSpans(true))

  // Fixme: Instantiation is a bit cumbersome
  def run(args: List[String]) =
    for {
      tracer      <- UIO(tracerConf.getTracer)
      rootSpan    <- UIO(tracer.buildSpan("REQUEST-ROOT").start())
      currentSpan <- FiberRef.make(rootSpan: io.opentracing.Span)
      r           = new Tracing.Live(new OpenTracingTracer(tracer), currentSpan) with Console.Live
      res         <- currentSpan.locally(rootSpan)(myAppLogic.fold(_ => 1, _ => 0).provide(r))
      _           <- UIO(rootSpan.finish())
    } yield res

  val myAppLogic =
    for {
      _      <- doSomething(1).instrumented("parent1")
      fiber1 <- doSomething(2).instrumented("parent2").fork
      fiber2 <- doSomething(3).instrumented("parent3").fork
      _      <- doSomething(4).instrumented("parent4")
      _      <- fiber1.join
      _      <- fiber2.join

      _ <- nonManuallyInstrumented(1).autoInstrument
      _ <- nonManuallyInstrumented(2).autoInstrument
      _ <- nonManuallyInstrumented(3).autoInstrument
    } yield ()

  def doSomething(n: Int) =
    for {
      _ <- putStrLn(s"Child 1 of Parent $n").instrumented(s"child1-of-parent$n", "child" -> 1, "parent"  -> n)
      _ <- log("LEVEL"                                                                   -> "DEBUG", "n" -> n, "isFoo" -> true)
      _ <- putStrLn(s"Child 2 of Parent $n").instrumented(s"child2-of-parent$n", "child" -> 2, "parent"  -> n)
    } yield ()

  def nonManuallyInstrumented(n: Int) =
    for {
      _ <- putStrLn(s"Child 1 of Parent $n")
      _ <- putStrLn(s"Child 2 of Parent $n")
      _ <- putStrLn(s"Child 3 of Parent $n")
    } yield ()
}
