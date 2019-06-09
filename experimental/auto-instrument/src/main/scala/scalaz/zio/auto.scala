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

package scalaz.zio // to access private ZIO tags

import _root_.zio.instrumentation._

object experimental {
  def auto[R <: Tracing[_], E, A, X1](self: ZIO[R, E, A]): ZIO[R, E, A] = self.tag match {
    case ZIO.Tags.FlatMap =>
      val flatMap = self.asInstanceOf[ZIO.FlatMap[R, E, X1, A]]
      for {
        x      <- auto(flatMap.zio)
        ztrace <- ZIO.trace
        a      <- flatMap.k(x).instrumented("FlatMap " + ztrace.stackTrace.drop(1).head.prettyPrint)
      } yield a
    case ZIO.Tags.Succeed =>
      self.instrumented("Succeed")
    case ZIO.Tags.EffectTotal =>
      for {
        ztrace <- ZIO.trace
        a      <- self.instrumented("EffectTotal" + ztrace.parentTrace.get.stackTrace.head.prettyPrint)
      } yield a
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
      new ZIO.Read((r: R) => auto(read.k(r))).instrumented("Read")
    case ZIO.Tags.Provide        => ???
    case ZIO.Tags.SuspendWith    => ???
    case ZIO.Tags.FiberRefNew    => ???
    case ZIO.Tags.FiberRefModify => ???
    case ZIO.Tags.Trace          => ???
    case ZIO.Tags.TracingStatus  => ???
    case ZIO.Tags.CheckTracing   => ???
  }
}
