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

package zio

import scalaz.zio._

package object instrumentation {
  implicit class InstrumentedZioSyntax[R <: Tracing[_], E, A](val self: ZIO[R, E, A]) extends AnyVal {
    def instrumented(operationName: String): ZIO[R, E, A] =
      ZIO.access[R](_.tracing).flatMap(_.useChild(operationName, self))
  }
}
