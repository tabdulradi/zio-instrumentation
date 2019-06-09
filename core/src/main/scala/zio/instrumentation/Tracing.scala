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

class Tracing[Span](val tracing: Propagator[Span]) extends Serializable

class Propagator[Span](backend: Backend[Span], ref: FiberRef[Span]) {
  def get: UIO[Span] = ref.get
  def useChild[R, E, A](operationName: String, use: ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.bracket(get.flatMap(backend.child(operationName, _)))(backend.close)(span => ref.locally(span)(use))
}
object Propagator {
  // Called by HTTP Frameworks for every request
  def make[Span](backend: Backend[Span], initial: Span): UIO[Propagator[Span]] =
    FiberRef.make[Span](initial).map(new Propagator(backend, _))
}

trait Backend[Span] {
  def root(operationName: String): UIO[Span]
  def child(operationName: String, parent: Span): UIO[Span]
  def close(span: Span): UIO[Unit]
}
