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

package zio.instrumentation

import scalaz.zio._
import io.opencensus.trace._
import scala.collection.JavaConverters._

object opencensus {
  type F[A] = ZIO[Tracing[Span], Nothing, A]

  private def ffiA[A](f: Span => A): F[A] =
    ZIO.accessM[Tracing[Span]](_.tracing.get).flatMap(span => UIO(f(span)))

  private def ffi(f: Span => Any): F[Unit] =
    ffiA(f).unit

  private def attributesFromScala(attributes: Seq[(String, ZAttributeValue)]) =
    attributes.toMap.mapValues(_.underlying).asJava

  def put(attributes: (String, ZAttributeValue)*): F[Unit] =
    ffi(_.putAttributes(attributesFromScala(attributes)))

  def annotate(description: String, attributes: (String, ZAttributeValue)*): F[Unit] =
    ffi(_.addAnnotation(Annotation.fromDescriptionAndAttributes(description, attributesFromScala(attributes))))
}

final case class ZAttributeValue(underlying: AttributeValue) extends AnyVal
object ZAttributeValue {
  import AttributeValue._

  implicit def attributeValueFromString(value: String): ZAttributeValue =
    ZAttributeValue(stringAttributeValue(value))

  implicit def attributeValueFromBoolean(value: Boolean): ZAttributeValue =
    ZAttributeValue(booleanAttributeValue(value))

  implicit def attributeValueFromDouble(value: Double): ZAttributeValue =
    ZAttributeValue(doubleAttributeValue(value))

  implicit def attributeValueFromLong(value: Long): ZAttributeValue =
    ZAttributeValue(longAttributeValue(value))
}

class OpenCensusBackend(tracer: Tracer) extends Backend[Span] {
  def root(operationName: String): UIO[Span] =
    child(operationName, null)

  def child(operationName: String, parent: Span): UIO[Span] =
    UIO(tracer.spanBuilderWithExplicitParent(operationName, parent).startSpan())

  def close(span: Span): UIO[Unit] = UIO(span.end())
}
