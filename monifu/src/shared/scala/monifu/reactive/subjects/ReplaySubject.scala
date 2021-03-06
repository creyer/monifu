package monifu.reactive.subjects

import scala.concurrent.Future
import monifu.reactive.api.Ack.{Continue, Done}
import monifu.concurrent.Scheduler
import monifu.reactive.Observer
import monifu.concurrent.atomic.padded.Atomic
import scala.annotation.tailrec
import monifu.reactive.api.Ack
import monifu.reactive.observers.ConnectableObserver
import scala.collection.immutable.Queue


/**
 * `ReplaySubject` emits to any observer all of the items that were emitted
 * by the source, regardless of when the observer subscribes.
 *
 * <img src="https://raw.githubusercontent.com/wiki/alexandru/monifu/assets/rx-operators/S.ReplaySubject.png" />
 */
final class ReplaySubject[T] private (s: Scheduler) extends Subject[T,T] { self =>
  import ReplaySubject.State
  import ReplaySubject.State._

  implicit val scheduler = s
  private[this] val state = Atomic(Empty(Queue.empty) : State[T])

  def subscribeFn(observer: Observer[T]): Unit = {
    @tailrec
    def loop(): ConnectableObserver[T] = {
      state.get match {
        case current @ Empty(cache) =>
          val obs = new ConnectableObserver[T](observer)
          obs.scheduleFirst(cache : _*)

          if (!state.compareAndSet(current, Active(Array(obs), cache)))
            loop()
          else
            obs

        case current @ Active(observers, cache) =>
          val obs = new ConnectableObserver[T](observer)
          obs.scheduleFirst(cache : _*)

          if (!state.compareAndSet(current, Active(observers :+ obs, cache)))
            loop()
          else
            obs

        case current @ Complete(cache, errorThrown) =>
          val obs = new ConnectableObserver[T](observer)
          if (errorThrown eq null) {
            obs.scheduleFirst(cache : _*)
            obs.scheduleComplete()
          }
          else {
            obs.scheduleFirst(cache : _*)
            obs.schedulerError(errorThrown)
          }
          obs
      }
    }

    loop().connect()
  }

  private[this] def emitNext(obs: Observer[T], elem: T): Future[Continue] =
    obs.onNext(elem) match {
      case Continue => Continue
      case Done =>
        removeSubscription(obs)
        Continue
      case other =>
        other.map {
          case Continue => Continue
          case Done =>
            removeSubscription(obs)
            Continue
        }
    }

  @tailrec
  def onNext(elem: T): Future[Ack] = {
    state.get match {
      case current @ Empty(_) =>
        if (!state.compareAndSet(current, Empty(current.cache.enqueue(elem))))
          onNext(elem)
        else
          Continue

      case current @ Active(observers, cache) =>
        if (!state.compareAndSet(current, Active(observers, cache.enqueue(elem))))
          onNext(elem)

        else {
          var idx = 0
          var acc = Continue : Future[Continue]

          while (idx < observers.length) {
            val obs = observers(idx)
            acc =
              if (acc == Continue || (acc.isCompleted && acc.value.get.isSuccess))
                emitNext(obs, elem)
              else {
                val f = emitNext(obs, elem)
                acc.flatMap(_ => f)
              }

            idx += 1
          }

          acc
        }

      case _ =>
        Done
    }
  }

  @tailrec
  def onComplete(): Unit =
    state.get match {
      case current @ Empty(cache) =>
        if (!state.compareAndSet(current, Complete(cache, null))) {
          onComplete() // retry
        }
      case current @ Active(observers, cache) =>
        if (!state.compareAndSet(current, Complete(cache, null))) {
          onComplete() // retry
        }
        else {
          var idx = 0
          while (idx < observers.length) {
            observers(idx).onComplete()
            idx += 1
          }
        }
      case _ =>
      // already complete, ignore
    }

  @tailrec
  def onError(ex: Throwable): Unit =
    state.get match {
      case current @ Empty(cache) =>
        if (!state.compareAndSet(current, Complete(cache, ex))) {
          onError(ex) // retry
        }
      case current @ Active(observers, cache) =>
        if (!state.compareAndSet(current, Complete(cache, ex))) {
          onError(ex) // retry
        }
        else {
          var idx = 0
          while (idx < observers.length) {
            observers(idx).onError(ex)
            idx += 1
          }
        }
      case _ =>
      // already complete, ignore
    }

  private[this] def removeSubscription(obs: Observer[T]): Unit =
    state.transform {
      case current @ Active(observers,_) =>
        current.copy(observers.filterNot(_ eq obs))
      case other =>
        other
    }
}

object ReplaySubject {
  def apply[T]()(implicit scheduler: Scheduler): ReplaySubject[T] =
    new ReplaySubject[T](scheduler)

  private sealed trait State[T]
  private object State {
    case class Empty[T](cache: Queue[T]) extends State[T]
    case class Active[T](iterator: Array[ConnectableObserver[T]], cache: Queue[T]) extends State[T]
    case class Complete[T](cache: Queue[T], errorThrown: Throwable = null) extends State[T]
  }
}