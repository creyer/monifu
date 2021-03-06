package monifu.reactive

import language.implicitConversions
import monifu.concurrent.{Cancelable, Scheduler}
import scala.concurrent.{Promise, Future}
import monifu.reactive.api._
import Ack.{Done, Continue}
import monifu.concurrent.atomic.Atomic
import monifu.reactive.cancelables._
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.control.NonFatal
import scala.collection.mutable
import scala.annotation.tailrec
import collection.JavaConverters._
import scala.util.{Failure, Success}
import monifu.reactive.subjects.{ReplaySubject, BehaviorSubject, PublishSubject, Subject}
import monifu.reactive.api.Notification.{OnComplete, OnNext, OnError}
import monifu.reactive.internals.MergeBuffer
import monifu.reactive.observers.{SafeObserver, BufferedObserver}


/**
 * Asynchronous implementation of the Observable interface
 */
trait Observable[+T] { self =>
  /**
   * Function that creates the actual subscription when calling `subscribe`,
   * and that starts the stream, being meant to be overridden in custom combinators
   * or in classes implementing Observable.
   *
   * @param observer is an [[Observer]] on which `onNext`, `onComplete` and `onError`
   *                 happens, according to the Rx grammar.
   *
   * @return a cancelable that can be used to cancel the streaming
   */
  protected def subscribeFn(observer: Observer[T]): Unit

  /**
   * Implicit scheduler required for asynchronous boundaries.
   */
  implicit def scheduler: Scheduler

  final def subscribe(observer: Observer[T]): Unit =
    subscribeFn(SafeObserver[T](observer))

  /**
   * Helper to be used by consumers for subscribing to an observable.
   */
  final def subscribe(nextFn: T => Future[Ack], errorFn: Throwable => Unit, completedFn: () => Unit): Unit =
    subscribe(new Observer[T] {
      def onNext(elem: T) = nextFn(elem)
      def onError(ex: Throwable) = errorFn(ex)
      def onComplete() = completedFn()
    })

  /**
   * Helper to be used by consumers for subscribing to an observable.
   */
  final def subscribe(nextFn: T => Future[Ack], errorFn: Throwable => Unit): Unit =
    subscribe(nextFn, errorFn, () => Done)

  /**
   * Helper to be used by consumers for subscribing to an observable.
   */
  final def subscribe(nextFn: T => Future[Ack]): Unit =
    subscribe(nextFn, error => { scheduler.reportFailure(error); Done }, () => Done)

  /**
   * Helper to be used by consumers for subscribing to an observable.
   */
  final def subscribe(): Unit =
    subscribe(elem => Continue)

  /**
   * Returns an Observable that applies the given function to each item emitted by an
   * Observable and emits the result.
   *
   * @param f a function to apply to each item emitted by the Observable
   * @return an Observable that emits the items from the source Observable, transformed by the given function
   */
  final def map[U](f: T => U): Observable[U] =
    Observable.create { observer =>
      subscribeFn(new Observer[T] {
        def onNext(elem: T) = {
          // See Section 6.4. in the Rx Design Guidelines:
          // Protect calls to user code from within an operator
          var streamError = true
          try {
            val next = f(elem)
            streamError = false
            observer.onNext(next)
          }
          catch {
            case NonFatal(ex) =>
              if (streamError) { observer.onError(ex); Done } else Future.failed(ex)
          }
        }

        def onError(ex: Throwable) =
          observer.onError(ex)

        def onComplete() =
          observer.onComplete()
      })
    }

  /**
   * Returns an Observable which only emits those items for which the given predicate holds.
   *
   * @param p a function that evaluates the items emitted by the source Observable, returning `true` if they pass the filter
   * @return an Observable that emits only those items in the original Observable for which the filter evaluates as `true`
   */
  final def filter(p: T => Boolean): Observable[T] =
    Observable.create { observer =>
      subscribeFn(new Observer[T] {
        def onNext(elem: T) = {
          // See Section 6.4. in the Rx Design Guidelines:
          // Protect calls to user code from within an operator
          var streamError = true
          try {
            if (p(elem)) {
              streamError = false
              observer.onNext(elem)
            }
            else
              Continue
          }
          catch {
            case NonFatal(ex) =>
              if (streamError) { observer.onError(ex); Done } else Future.failed(ex)
          }
        }

        def onError(ex: Throwable) =
          observer.onError(ex)

        def onComplete() =
          observer.onComplete()
      })
    }

  final def foreach(cb: T => Unit): Unit =
    subscribeFn(new Observer[T] {
      def onNext(elem: T) =
        try { cb(elem); Continue } catch {
          case NonFatal(ex) =>
            onError(ex)
            Done
        }

      def onComplete() = Done
      def onError(ex: Throwable) = {
        scheduler.reportFailure(ex)
        Done
      }
    })

  /**
   * Creates a new Observable by applying a function that you supply to each item emitted by
   * the source Observable, where that function returns an Observable, and then concatenating those
   * resulting Observables and emitting the results of this concatenation.
   *
   * @param f a function that, when applied to an item emitted by the source Observable, returns an Observable
   * @return an Observable that emits the result of applying the transformation function to each
   *         item emitted by the source Observable and concatenating the results of the Observables
   *         obtained from this transformation.
   */
  final def flatMap[U](f: T => Observable[U]): Observable[U] =
    map(f).flatten

  /**
   * Creates a new Observable by applying a function that you supply to each item emitted by
   * the source Observable, where that function returns an Observable, and then concatenating those
   * resulting Observables and emitting the results of this concatenation.
   *
   * @param f a function that, when applied to an item emitted by the source Observable, returns an Observable
   * @return an Observable that emits the result of applying the transformation function to each
   *         item emitted by the source Observable and concatenating the results of the Observables
   *         obtained from this transformation.
   */
  final def concatMap[U](f: T => Observable[U]): Observable[U] =
    map(f).concat

  /**
   * Creates a new Observable by applying a function that you supply to each item emitted by
   * the source Observable, where that function returns an Observable, and then merging those
   * resulting Observables and emitting the results of this merger.
   *
   * @param f a function that, when applied to an item emitted by the source Observable, returns an Observable
   * @return an Observable that emits the result of applying the transformation function to each
   *         item emitted by the source Observable and merging the results of the Observables
   *         obtained from this transformation.
   */
  final def mergeMap[U](f: T => Observable[U]): Observable[U] =
    map(f).merge

  /**
   * Flattens the sequence of Observables emitted by the source into one Observable, without any
   * transformation.
   *
   * You can combine the items emitted by multiple Observables so that they act like a single
   * Observable by using this method.
   *
   * This operation is only available if `this` is of type `Observable[Observable[B]]` for some `B`,
   * otherwise you'll get a compilation error.
   *
   * @return an Observable that emits items that are the result of flattening the items emitted
   *         by the Observables emitted by `this`
   */
  final def flatten[U](implicit ev: T <:< Observable[U]): Observable[U] = concat

  /**
   * Concatenates the sequence of Observables emitted by the source into one Observable, without any
   * transformation.
   *
   * You can combine the items emitted by multiple Observables so that they act like a single
   * Observable by using this method.
   *
   * The difference between [[concat]] and [[merge]] is that `concat` cares about ordering of
   * emitted items (e.g. all items emitted by the first observable in the sequence will come before
   * the elements emitted by the second observable), whereas `merge` doesn't care about that
   * (elements get emitted as they come). Because of back-pressure applied to observables,
   * [[concat]] is safe to use in all contexts, whereas [[merge]] requires buffering.
   *
   * @return an Observable that emits items that are the result of flattening the items emitted
   *         by the Observables emitted by `this`
   */
  final def concat[U](implicit ev: T <:< Observable[U]): Observable[U] =
    Observable.create { observerU =>
      subscribeFn(new Observer[T] {
        def onNext(childObservable: T) = {
          val upstreamPromise = Promise[Ack]()

          childObservable.subscribeFn(new Observer[U] {
            def onNext(elem: U) =
              observerU.onNext(elem)

            def onError(ex: Throwable) = {
              // error happened, so signaling both the main thread that it should stop
              // and the downstream consumer of the error
              observerU.onError(ex)
              upstreamPromise.success(Done)
            }

            def onComplete() = {
              // NOTE: we aren't sending this onComplete signal downstream to our observerU
              // instead we are just instructing upstream to send the next observable
              upstreamPromise.success(Continue)
            }
          })

          upstreamPromise.future
        }

        def onError(ex: Throwable) = {
          // oops, error happened on main thread, piping that along should cancel everything
          observerU.onError(ex)
        }

        def onComplete() = {
          // at this point all children observables have ended
          observerU.onComplete()
        }
      })
    }

  /**
   * Merges the sequence of Observables emitted by the source into one Observable, without any
   * transformation.
   *
   * You can combine the items emitted by multiple Observables so that they act like a single
   * Observable by using this method.
   *
   * The difference between [[concat]] and [[merge]] is that `concat` cares about ordering of
   * emitted items (e.g. all items emitted by the first observable in the sequence will come before
   * the elements emitted by the second observable), whereas `merge` doesn't care about that
   * (elements get emitted as they come). Because of back-pressure applied to observables,
   * [[concat]] is safe to use in all contexts, whereas [[merge]] requires buffering.
   *
   * @return an Observable that emits items that are the result of flattening the items emitted
   *         by the Observables emitted by `this`
   */
  final def merge[U](implicit ev: T <:< Observable[U]): Observable[U] = {
    Observable.create { observerB =>
      val ackBuffer = new MergeBuffer

      // we need to do ref-counting for triggering `onComplete` on our subscriber
      // when all the children threads have ended
      val refCounter = RefCountCancelable {
        ackBuffer.scheduleDone(observerB.onComplete())
      }

      subscribeFn(new Observer[T] {
        def onNext(elem: T) = {
          // reference that gets released when the child observer is completed
          val refID = refCounter.acquire()

          elem.subscribeFn(new Observer[U] {
            def onNext(elem: U) =
              ackBuffer.scheduleNext {
                observerB.onNext(elem)
              }

            def onError(ex: Throwable) = {
              // onError, cancel everything
              ackBuffer.scheduleDone(observerB.onError(ex))
            }

            def onComplete() = {
              // do resource release, otherwise we can end up with a memory leak
              refID.cancel()
            }
          })

          ackBuffer.scheduleNext(Continue)
        }

        def onError(ex: Throwable) =
          ackBuffer.scheduleDone(observerB.onError(ex))

        def onComplete() = {
          // triggers observer.onComplete() when all Observables created have been finished
          // basically when the main thread is completed, it waits to stream onComplete
          // until all children have been onComplete too - only after that `subscriber.onComplete` gets triggered
          // (see `RefCountCancelable` for details on how it works)
          refCounter.cancel()
        }
      })
    }
  }

  /**
   * Selects the first ''n'' elements (from the start).
   *
   *  @param  n  the number of elements to take
   *  @return    a new Observable that emits only the first ''n'' elements from the source
   */
  final def take(n: Int): Observable[T] =
    Observable.create { observer =>
      subscribeFn(new Observer[T] {
        private[this] var counter = 0

        def onNext(elem: T) = {
          // short-circuit for not endlessly incrementing that number
          if (counter < n) {
            counter += 1

            if (counter < n) {
              // this is not the last event in the stream, so send it directly
              observer.onNext(elem)
            }
            else  {
              // last event in the stream, so we need to send the event followed by an EOF downstream
              // after which we signal upstream to the producer that it should stop
              observer.onNext(elem).flatMap {
                case Done => Done
                case Continue =>
                  observer.onComplete()
                  Done
              }
            }
          }
          else {
            // we already emitted the maximum number of events, so signal upstream
            // to the producer that it should stop sending events
            Done
          }
        }

        def onError(ex: Throwable) =
          observer.onError(ex)

        def onComplete() =
          observer.onComplete()
      })
    }

  final def takeRight(n: Int): Observable[T] =
    Observable.create { observer =>
      subscribe(new Observer[T] {
        private[this] val queue = mutable.Queue.empty[T]
        private[this] var queued = 0

        def onNext(elem: T): Future[Ack] = {
          if (queued < n) {
            queue.enqueue(elem)
            queued += 1
          }
          else {
            queue.enqueue(elem)
            queue.dequeue()
          }
          Continue
        }

        def onError(ex: Throwable): Unit = {
          queue.clear()
          observer.onError(ex)
        }

        def onComplete(): Unit = {
          Observable.fromIterable(queue).subscribeFn(observer)
        }
      })
    }

  /**
   * Drops the first ''n'' elements (from the start).
   *
   *  @param  n  the number of elements to drop
   *  @return    a new Observable that drops the first ''n'' elements
   *             emitted by the source
   */
  final def drop(n: Int): Observable[T] =
    Observable.create { observer =>
      subscribeFn(new Observer[T] {
        private[this] var count = 0L

        def onNext(elem: T) = {
          if (count < n) {
            count += 1
            Continue
          }
          else
            observer.onNext(elem)
        }

        def onComplete() =
          observer.onComplete()

        def onError(ex: Throwable) =
          observer.onError(ex)
      })
    }

  /**
   * Takes longest prefix of elements that satisfy the given predicate
   * and returns a new Observable that emits those elements.
   */
  final def takeWhile(p: T => Boolean): Observable[T] =
    Observable.create { observer =>
      subscribeFn(new Observer[T] {
        var shouldContinue = true

        def onNext(elem: T) = {
          if (shouldContinue) {
            // See Section 6.4. in the Rx Design Guidelines:
            // Protect calls to user code from within an operator
            var streamError = true
            try {
              val isValid = p(elem)
              streamError = false
              if (isValid)
                observer.onNext(elem)
              else {
                shouldContinue = false
                observer.onComplete()
                Done
              }
            }
            catch {
              case NonFatal(ex) =>
                if (streamError) { observer.onError(ex); Done } else Future.failed(ex)
            }
          }
          else
            Done
        }

        def onComplete() =
          observer.onComplete()

        def onError(ex: Throwable) =
          observer.onError(ex)
      })
    }

  /**
   * Takes longest prefix of elements that satisfy the given predicate
   * and returns a new Observable that emits those elements.
   */
  final def takeWhile(isRefTrue: Atomic[Boolean]): Observable[T] =
    Observable.create { observer =>
      subscribeFn(new Observer[T] {
        var shouldContinue = true

        def onNext(elem: T) = {
          if (shouldContinue) {
            // See Section 6.4. in the Rx Design Guidelines:
            // Protect calls to user code from within an operator
            var streamError = true
            try {
              val continue = isRefTrue.get
              streamError = false

              if (continue)
                observer.onNext(elem)
              else {
                shouldContinue = false
                observer.onComplete()
                Done
              }
            }
            catch {
              case NonFatal(ex) =>
                if (streamError) { observer.onError(ex); Done } else Future.failed(ex)
            }
          }
          else
            Done
        }

        def onComplete() =
          observer.onComplete()

        def onError(ex: Throwable) =
          observer.onError(ex)
      })
    }

  /**
   * Drops the longest prefix of elements that satisfy the given predicate
   * and returns a new Observable that emits the rest.
   */
  final def dropWhile(p: T => Boolean): Observable[T] =
    Observable.create { observer =>
      subscribeFn(new Observer[T] {
        var shouldDrop = true

        def onNext(elem: T) = {
          if (shouldDrop) {
            // See Section 6.4. in the Rx Design Guidelines:
            // Protect calls to user code from within an operator
            var streamError = true
            try {
              val isInvalid = p(elem)
              streamError = false

              if (isInvalid)
                Continue
              else {
                shouldDrop = false
                observer.onNext(elem)
              }
            }
            catch {
              case NonFatal(ex) =>
                if (streamError) { observer.onError(ex); Done } else Future.failed(ex)
            }
          }
          else
            observer.onNext(elem)
        }

        def onComplete() =
          observer.onComplete()

        def onError(ex: Throwable) =
          observer.onError(ex)
      })
    }

  /**
   * Applies a binary operator to a start value and all elements of this Observable,
   * going left to right and returns a new Observable that emits only one item
   * before `onComplete`.
   */
  final def foldLeft[R](initial: R)(op: (R, T) => R): Observable[R] =
    Observable.create { observer =>
      subscribeFn(new Observer[T] {
        private[this] var state = initial

        def onNext(elem: T): Future[Ack] = {
          // See Section 6.4. in the Rx Design Guidelines:
          // Protect calls to user code from within an operator
          try {
            state = op(state, elem)
            Continue
          }
          catch {
            case NonFatal(ex) =>
              onError(ex)
              Done
          }
        }

        def onComplete() =
          observer.onNext(state).onSuccess {
            case Continue => observer.onComplete()
          }

        def onError(ex: Throwable) =
          observer.onError(ex)
      })
    }

  /**
   * Applies a binary operator to a start value and all elements of this Observable,
   * going left to right and returns a new Observable that emits only one item
   * before `onComplete`.
   */
  final def reduce[U >: T](op: (U, U) => U): Observable[U] =
    Observable.create { observer =>
      subscribeFn(new Observer[T] {
        private[this] var state: U = _
        private[this] var isFirst = true
        private[this] var wasApplied = false

        def onNext(elem: T): Future[Ack] = {
          // See Section 6.4. in the Rx Design Guidelines:
          // Protect calls to user code from within an operator
          try {
            if (isFirst) {
              isFirst = false
              state = elem
            }
            else {
              state = op(state, elem)
              if (!wasApplied) wasApplied = true
            }

            Continue
          }
          catch {
            case NonFatal(ex) =>
              onError(ex)
              Done
          }
        }

        def onComplete() =
          if (wasApplied)
            observer.onNext(state).onSuccess {
              case Continue => observer.onComplete()
            }
          else
            observer.onComplete()

        def onError(ex: Throwable) = {
          observer.onError(ex)
        }
      })
    }

  /**
   * Applies a binary operator to a start value and all elements of this Observable,
   * going left to right and returns a new Observable that emits on each step the result
   * of the applied function.
   *
   * Similar to [[foldLeft]], but emits the state on each step. Useful for modeling finite
   * state machines.
   */
  final def scan[R](initial: R)(op: (R, T) => R): Observable[R] =
    Observable.create { observer =>
      subscribeFn(new Observer[T] {
        private[this] var state = initial

        def onNext(elem: T): Future[Ack] = {
          // See Section 6.4. in the Rx Design Guidelines:
          // Protect calls to user code from within an operator
          var streamError = true
          try {
            state = op(state, elem)
            streamError = false
            observer.onNext(state)
          }
          catch {
            case NonFatal(ex) =>
              if (streamError) { observer.onError(ex); Done } else Future.failed(ex)
          }
        }

        def onComplete() =
          observer.onComplete()

        def onError(ex: Throwable) =
          observer.onError(ex)
      })
    }

  /**
   * Applies a binary operator to the first item emitted by a source Observable,
   * then feeds the result of that function along with the second item emitted by
   * the source Observable into the same function, and so on until all items have been
   * emitted by the source Observable, emitting the result of each of these iterations.
   *
   * Similar to [[reduce]], but emits the state on each step. Useful for modeling finite
   * state machines.
   */
  final def scan[U >: T](op: (U, U) => U): Observable[U] =
    Observable.create { observer =>
      subscribeFn(new Observer[T] {
        private[this] var state: U = _
        private[this] var isFirst = true

        def onNext(elem: T): Future[Ack] = {
          // See Section 6.4. in the Rx Design Guidelines:
          // Protect calls to user code from within an operator
          var streamError = true
          try {
            if (isFirst) {
              state = elem
              isFirst = false
            }
            else
              state = op(state, elem)

            streamError = false
            observer.onNext(state)
          }
          catch {
            case NonFatal(ex) =>
              if (streamError) { observer.onError(ex); Done } else Future.failed(ex)
          }
        }

        def onComplete() =
          observer.onComplete()

        def onError(ex: Throwable) =
          observer.onError(ex)
      })
    }

  /**
   * Executes the given callback when the stream has ended on `onComplete`
   * (after the event was already emitted)
   *
   * NOTE: protect the callback such that it doesn't throw exceptions, because
   * it gets executed after `onComplete()` happens and by definition the error cannot
   * be streamed with `onError()`.
   *
   * @param cb the callback to execute when the subscription is canceled
   */
  final def doOnComplete(cb: => Unit): Observable[T] =
    Observable.create { observer =>
      subscribeFn(new Observer[T] {
        def onNext(elem: T) = {
          val f = observer.onNext(elem)
          f.onSuccess { case Done => cb }
          f
        }

        def onError(ex: Throwable): Unit =
          observer.onError(ex)

        def onComplete(): Unit = {
          try observer.onComplete() finally {
            try cb catch {
              case NonFatal(ex) =>
                scheduler.reportFailure(ex)
            }
          }
        }
      })
    }

  /**
   * Executes the given callback for each element generated by the source
   * Observable, useful for doing side-effects.
   *
   * @return a new Observable that executes the specified callback for each element
   */
  final def doWork(cb: T => Unit): Observable[T] =
    Observable.create { observer =>
      subscribeFn(new Observer[T] {
        def onError(ex: Throwable) = observer.onError(ex)
        def onComplete() = observer.onComplete()

        def onNext(elem: T) = {
          // See Section 6.4. in the Rx Design Guidelines:
          // Protect calls to user code from within an operator
          var streamError = true
          try {
            cb(elem)
            streamError = false
            observer.onNext(elem)
          }
          catch {
            case NonFatal(ex) =>
              if (streamError) { observer.onError(ex); Done } else Future.failed(ex)
          }
        }
      })
    }

  /**
   * Returns an Observable which only emits the first item for which the predicate holds.
   *
   * @param p a function that evaluates the items emitted by the source Observable, returning `true` if they pass the filter
   * @return an Observable that emits only the first item in the original Observable for which the filter evaluates as `true`
   */
  final def find(p: T => Boolean): Observable[T] =
    filter(p).head

  /**
   * Returns an Observable which emits a single value, either true, in case the given predicate holds for at least
   * one item, or false otherwise.
   *
   * @param p a function that evaluates the items emitted by the source Observable, returning `true` if they pass the filter
   * @return an Observable that emits only true or false in case the given predicate holds or not for at least one item
   */
  final def exists(p: T => Boolean): Observable[Boolean] =
    find(p).foldLeft(false)((_, _) => true)

  /**
   * Returns an Observable that emits a single boolean, either true, in case the given predicate holds for all the items
   * emitted by the source, or false in case at least one item is not verifying the given predicate.
   *
   * @param p a function that evaluates the items emitted by the source Observable, returning `true` if they pass the filter
   * @return an Observable that emits only true or false in case the given predicate holds or not for all the items
   */
  final def forAll(p: T => Boolean): Observable[Boolean] =
    exists(e => !p(e)).map(r => !r)

  /**
   * Returns an Observable that doesn't emit anything, but that completes when the source Observable completes.
   */
  final def complete: Observable[Nothing] =
    Observable.create { observer =>
      subscribeFn(new Observer[T] {
        def onNext(elem: T) = Continue
        def onError(ex: Throwable): Unit =
          observer.onError(ex)
        def onComplete(): Unit =
          observer.onComplete()
      })
    }

  /**
   * Returns an Observable that emits a single Throwable, in case an error was thrown by the source Observable,
   * otherwise it isn't going to emit anything.
   */
  final def error: Observable[Throwable] =
    Observable.create { observer =>
      subscribeFn(new Observer[T] {
        def onNext(elem: T) = Continue
        def onComplete(): Unit =
          observer.onComplete()

        def onError(ex: Throwable): Unit = {
          observer.onNext(ex).onSuccess {
            case Continue => observer.onComplete()
          }
        }
      })
    }

  /**
   * Returns the first generated result as a Future and then cancels
   * the subscription.
   */
  final def asFuture: Future[Option[T]] = {
    val promise = Promise[Option[T]]()

    head.subscribeFn(new Observer[T] {
      def onNext(elem: T) = {
        promise.trySuccess(Some(elem))
        Done
      }

      def onComplete() = {
        promise.trySuccess(None)
        Done
      }

      def onError(ex: Throwable) = {
        promise.tryFailure(ex)
        Done
      }
    })

    promise.future
  }

  /**
   * Concatenates the source Observable with the other Observable, as specified.
   */
  final def ++[U >: T](other: => Observable[U]): Observable[U] =
    Observable.fromSequence(Seq(this, other)).flatten

  /**
   * Only emits the first element emitted by the source observable, after which it's completed immediately.
   */
  final def head: Observable[T] = take(1)

  /**
   * Drops the first element of the source observable, emitting the rest.
   */
  final def tail: Observable[T] = drop(1)

  /**
   * Only emits the last element emitted by the source observable, after which it's completed immediately.
   */
  final def last: Observable[T] = takeRight(1)

  /**
   * Emits the first element emitted by the source, or otherwise if the source is completed without
   * emitting anything, then the `default` is emitted.
   */
  final def headOrElse[B >: T](default: => B): Observable[B] =
    head.foldLeft(Option.empty[B])((_, elem) => Some(elem)) map {
      case Some(elem) => elem
      case None => default
    }

  /**
   * Emits the first element emitted by the source, or otherwise if the source is completed without
   * emitting anything, then the `default` is emitted.
   *
   * Alias for `headOrElse`.
   */
  final def firstOrElse[U >: T](default: => U): Observable[U] =
    headOrElse(default)

  /**
   * Creates a new Observable from this Observable and another given Observable,
   * by emitting elements combined in pairs. If one of the Observable emits fewer
   * events than the other, then the rest of the unpaired events are ignored.
   */
  final def zip[U](other: Observable[U]): Observable[(T, U)] =
    Observable.create { observerOfPairs =>
      // using mutability, receiving data from 2 producers, so must synchronize
      val lock = new AnyRef
      val queueA = mutable.Queue.empty[(Promise[U], Promise[Ack])]
      val queueB = mutable.Queue.empty[(U, Promise[Ack])]
      var isCompleted = false

      def _onError(ex: Throwable) = lock.synchronized {
        if (!isCompleted) {
          isCompleted = true
          queueA.clear()
          queueB.clear()
          observerOfPairs.onError(ex)
        }
        else
          Done
      }

      subscribeFn(new Observer[T] {
        def onNext(a: T): Future[Ack] =
          lock.synchronized {
            if (queueB.isEmpty) {
              val resp = Promise[Ack]()
              val promiseForB = Promise[U]()
              queueA.enqueue((promiseForB, resp))

              val f = promiseForB.future.flatMap(b => observerOfPairs.onNext((a, b)))
              resp.completeWith(f)
              f
            }
            else {
              val (b, bResponse) = queueB.dequeue()
              val f = observerOfPairs.onNext((a, b))
              bResponse.completeWith(f)
              f
            }
          }

        def onError(ex: Throwable) =
          _onError(ex)

        def onComplete() = lock.synchronized {
          if (!isCompleted && queueA.isEmpty) {
            isCompleted = true
            queueA.clear()
            queueB.clear()
            observerOfPairs.onComplete()
          }
        }
      })

      other.subscribeFn(new Observer[U] {
        def onNext(b: U): Future[Ack] =
          lock.synchronized {
            if (queueA.nonEmpty) {
              val (bPromise, response) = queueA.dequeue()
              bPromise.success(b)
              response.future
            }
            else {
              val p = Promise[Ack]()
              queueB.enqueue((b, p))
              p.future
            }
          }

        def onError(ex: Throwable) = _onError(ex)

        def onComplete() = lock.synchronized {
          if (!isCompleted && queueB.isEmpty) {
            isCompleted = true
            queueA.clear()
            queueB.clear()
            observerOfPairs.onComplete()
          }
        }
      })
    }

  /**
   * Returns a new Observable that uses the specified `ExecutionContext` for listening to the emitted items.
   */
  final def observeOn(s: Scheduler): Observable[T] = {
    implicit val scheduler = s

    Observable.create { observer =>
      subscribeFn(new Observer[T] {
        private[this] var lastResponse = Continue : Future[Ack]

        def onNext(elem: T): Future[Ack] = {
          val newResponse = lastResponse.flatMap {
            case Done => Done
            case Continue =>
              observer.onNext(elem)
          }

          lastResponse = newResponse
          newResponse
        }

        def onError(ex: Throwable): Unit = {
          lastResponse = lastResponse.flatMap {
            case Done => Done
            case Continue =>
              observer.onError(ex)
              Done
          }
        }

        def onComplete(): Unit = {
          lastResponse = lastResponse.flatMap {
            case Done => Done
            case Continue =>
              observer.onComplete()
              Done
          }
        }
      })
    }
  }

  /**
   * Returns a new Observable that uses the specified `ExecutionContext` for initiating the subscription.
   */
  final def subscribeOn(s: Scheduler): Observable[T] = {
    implicit val scheduler = s
    Observable.create(o => s.scheduleOnce(subscribeFn(o)))
  }

  /**
   * Converts the source Observable that emits `T` into an Observable
   * that emits `Notification[T]`.
   *
   * NOTE: `onComplete` is still emitted after an `onNext(OnComplete)` notification
   * however an `onError(ex)` notification is emitted as an `onNext(OnError(ex))`
   * followed by an `onComplete`.
   */
  final def materialize: Observable[Notification[T]] =
    Observable.create { observer =>
      subscribeFn(new Observer[T] {
        def onNext(elem: T): Future[Ack] =
          observer.onNext(OnNext(elem))

        def onError(ex: Throwable): Unit =
          observer.onNext(OnError(ex)).onSuccess {
            case Continue => observer.onComplete()
          }

        def onComplete(): Unit =
          observer.onNext(OnComplete).onSuccess {
            case Continue => observer.onComplete()
          }
      })
    }

  /**
   * Utility that can be used for debugging purposes.
   */
  final def dump(prefix: String): Observable[T] =
    Observable.create { observer =>
      subscribeFn(new Observer[T] {
        private[this] var pos = 0

        def onNext(elem: T): Future[Ack] = {
          println(s"$pos: $prefix-->$elem")
          pos += 1
          observer.onNext(elem)
        }

        def onError(ex: Throwable) = {
          println(s"$pos: $prefix-->$ex")
          pos += 1
          observer.onError(ex)
        }

        def onComplete() = {
          println(s"$pos: $prefix completed")
          pos += 1
          observer.onComplete()
        }
      })
    }

  /**
   * Converts this observable into a multicast observable, useful for turning a cold observable into
   * a hot one (i.e. whose source is shared by all observers).
   */
  final def multicast[R](subject: Subject[T, R]): ConnectableObservable[R] =
    new ConnectableObservable[R] {
      private[this] val notCanceled = Atomic(true)
      val scheduler = self.scheduler

      private[this] val cancelAction =
        BooleanCancelable { notCanceled set false }
      private[this] val notConnected =
        Cancelable { self.takeWhile(notCanceled).subscribeFn(subject) }

      def connect() = {
        notConnected.cancel()
        cancelAction
      }

      def subscribeFn(observer: Observer[R]): Unit = {
        subject.subscribeFn(observer)
      }
    }

  /**
   * Wraps the observer implementation given to `subscribeFn` into a [[observers.SafeObserver SafeObserver]].
   * Normally wrapping in a `SafeObserver` happens at the edges of the monad
   * (in the user-facing `subscribe()` implementation) or in Observable subscribe implementations,
   * so this wrapping is useful.
   */
  final def safe: Observable[T] =
    Observable.create { observer => subscribeFn(SafeObserver(observer)) }

  /**
   * Wraps the observer implementation given to `subscribeFn` into a [[observers.BufferedObserver BufferedObserver]].
   *
   * Normally Monifu's implementation guarantees that events are not emitted concurrently,
   * and that the publisher MUST NOT emit the next event without acknowledgement from the consumer
   * that it may proceed, however for badly behaved publishers, this wrapper provides
   * the guarantee that the downstream [[Observer]] given in `subscribe` will not receive
   * concurrent events, also making it thread-safe.
   *
   * WARNING: the JVM's process might blow up if the producer is emitting events too fast,
   * because the buffer is unbounded.
   */
  final def buffered: Observable[T] =
    Observable.create { observer => subscribeFn(BufferedObserver(observer)) }

  /**
   * An alias for [[buffered]]. Wraps the observer implementation given to `subscribeFn`
   * into a [[observers.BufferedObserver BufferedObserver]].
   *
   * Normally Monifu's implementation guarantees that events are not emitted concurrently,
   * and that the publisher MUST NOT emit the next event without acknowledgement from the consumer
   * that it may proceed, however for badly behaved publishers, this wrapper provides
   * the guarantee that the downstream [[Observer]] given in `subscribe` will not receive
   * concurrent events, also making it thread-safe.
   *
   * WARNING: the JVM's process might blow up if the producer is emitting events too fast,
   * because the buffer is unbounded.   */
  final def sync: Observable[T] = buffered

  /**
   * Converts this observable into a multicast observable, useful for turning a cold observable into
   * a hot one (i.e. whose source is shared by all observers). The underlying subject used is a
   * [[monifu.reactive.subjects.PublishSubject PublishSubject]].
   */
  final def publish(): ConnectableObservable[T] =
    multicast(PublishSubject())

  /**
   * Converts this observable into a multicast observable, useful for turning a cold observable into
   * a hot one (i.e. whose source is shared by all observers). The underlying subject used is a
   * [[monifu.reactive.subjects.BehaviorSubject BehaviorSubject]].
   */
  final def behavior[U >: T](initialValue: U): ConnectableObservable[U] =
    multicast(BehaviorSubject(initialValue))

  /**
   * Converts this observable into a multicast observable, useful for turning a cold observable into
   * a hot one (i.e. whose source is shared by all observers). The underlying subject used is a
   * [[monifu.reactive.subjects.ReplaySubject ReplaySubject]].
   */
  final def replay(): ConnectableObservable[T] =
    multicast(ReplaySubject())
}

object Observable {
  /**
   * Observable constructor for creating an [[Observable]] from the specified function.
   *
   * <img src="https://raw.githubusercontent.com/wiki/alexandru/monifu/assets/rx-operators/create.png" />
   *
   * Example: {{{
   *   import monifu.reactive._
   *   import monifu.reactive.api.Ack.Continue
   *   import monifu.concurrent.Scheduler
   *
   *   def emit[T](elem: T, nrOfTimes: Int)(implicit scheduler: Scheduler): Observable[T] =
   *     Observable.create { observer =>
   *       def loop(times: Int): Unit =
   *         scheduler.scheduleOnce {
   *           if (times > 0)
   *             observer.onNext(elem).onSuccess {
   *               case Continue => loop(times - 1)
   *             }
   *           else
   *             observer.onComplete()
   *         }
   *       loop(nrOfTimes)
   *     }
   *
   *   // usage sample
   *   import monifu.concurrent.Scheduler.Implicits.global

   *   emit(elem=30, nrOfTimes=3).dump("Emit").subscribe()
   *   //=> 0: Emit-->30
   *   //=> 1: Emit-->30
   *   //=> 2: Emit-->30
   *   //=> 3: Emit completed
   * }}}
   */
  def create[T](f: Observer[T] => Unit)(implicit scheduler: Scheduler): Observable[T] = {
    val s = scheduler
    new Observable[T] {
      val scheduler = s
      def subscribeFn(observer: Observer[T]): Unit =
        try f(observer) catch {
          case NonFatal(ex) =>
            observer.onError(ex)
        }
    }
  }

  /**
   * Creates an observable that doesn't emit anything, but immediately calls `onComplete`
   * instead.
   *
   * <img src="https://raw.githubusercontent.com/wiki/alexandru/monifu/assets/rx-operators/empty.png" />
   */
  def empty[A](implicit scheduler: Scheduler): Observable[A] =
    Observable.create { observer =>
      SafeObserver(observer).onComplete()
    }

  /**
   * Creates an Observable that only emits the given ''a''
   *
   * <img src="https://raw.githubusercontent.com/wiki/alexandru/monifu/assets/rx-operators/unit.png" />
   */
  def unit[A](elem: A)(implicit scheduler: Scheduler): Observable[A] = {
    Observable.create { o =>
      val observer = SafeObserver(o)
      observer.onNext(elem).onSuccess {
        case Continue =>
          observer.onComplete()
      }
    }
  }

  /**
   * Creates an Observable that emits an error.
   *
   * <img src="https://raw.githubusercontent.com/wiki/alexandru/monifu/assets/rx-operators/error.png" />
   */
  def error(ex: Throwable)(implicit scheduler: Scheduler): Observable[Nothing] =
    Observable.create { observer =>
      SafeObserver[Nothing](observer).onError(ex)
    }

  /**
   * Creates an Observable that doesn't emit anything and that never completes.
   *
   * <img src="https://raw.githubusercontent.com/wiki/alexandru/monifu/assets/rx-operators/never.png"" />
   */
  def never(implicit scheduler: Scheduler): Observable[Nothing] =
    Observable.create { _ => () }

  /**
   * Creates an Observable that emits auto-incremented natural numbers (longs) spaced by
   * a given time interval. Starts from 0 with no delay, after which it emits incremented
   * numbers spaced by the `period` of time.
   *
   * <img src="https://raw.githubusercontent.com/wiki/alexandru/monifu/assets/rx-operators/interval.png"" />
   *
   * @param period the delay between two subsequent events
   * @param scheduler the execution context in which `onNext` will get called
   */
  def interval(period: FiniteDuration)(implicit scheduler: Scheduler): Observable[Long] = {
    Observable.create { o =>
      val observer = SafeObserver(o)
      var counter = 0

      scheduler.scheduleRecursive(Duration.Zero, period, { reschedule =>
        val result = observer.onNext(counter)
        counter += 1

        result.onSuccess {
          case Continue =>
            reschedule()
        }
      })
    }
  }

  /**
   * Creates an Observable that continuously emits the given ''item'' repeatedly.
   */
  def continuous[T](elem: T)(implicit scheduler: Scheduler): Observable[T] =
    Observable.create { o =>
      val observer = SafeObserver(o)

      def loop(elem: T): Unit =
        scheduler.execute(new Runnable {
          def run(): Unit =
            observer.onNext(elem).onSuccess {
              case Continue =>
                loop(elem)
            }
        })

      loop(elem)
    }

  /**
   * Creates an Observable that emits items in the given range.
   *
   * <img src="https://raw.githubusercontent.com/wiki/alexandru/monifu/assets/rx-operators/range.png" />
   *
   * @param from the range start
   * @param until the range end
   * @param step increment step, either positive or negative
   */
  def range(from: Int, until: Int, step: Int = 1)(implicit scheduler: Scheduler): Observable[Int] = {
    require(step != 0, "step must be a number different from zero")

    Observable.create { o =>
      val observer = SafeObserver(o)

      def scheduleLoop(from: Int, until: Int, step: Int): Unit =
        scheduler.execute(new Runnable {
          @tailrec
          def loop(from: Int, until: Int, step: Int): Unit =
            if ((step > 0 && from < until) || (step < 0 && from > until)) {
              observer.onNext(from) match {
                case Continue =>
                  loop(from + step, until, step)
                case Done =>
                  // do nothing else
                case async =>
                  async.onSuccess {
                    case Continue =>
                      scheduleLoop(from + step, until, step)
                  }
              }
            }
            else
              observer.onComplete()

          def run(): Unit =
            loop(from, until, step)
        })

      scheduleLoop(from, until, step)
    }
  }


  /**
   * Creates an Observable that emits the elements of the given ''sequence''.
   *
   * <img src="https://raw.githubusercontent.com/wiki/alexandru/monifu/assets/rx-operators/fromSequence.png" />
   */
  def fromSequence[T](seq: Seq[T])(implicit scheduler: Scheduler): Observable[T] =
    Observable.create { o =>
      val observer = SafeObserver(o)

      def startFeedLoop(seq: Seq[T]): Unit =
        scheduler.execute(new Runnable {
          @tailrec
          def loop(seq: Seq[T]): Unit = {
            if (seq.nonEmpty) {
              val elem = seq.head
              val tail = seq.tail

              observer.onNext(elem) match {
                case Continue =>
                  loop(tail)
                case Done =>
                // do nothing else
                case async =>
                  async.onSuccess {
                    case Continue =>
                      startFeedLoop(tail)
                  }
              }
            }
            else
              observer.onComplete()
          }

          def run(): Unit =
            try { loop(seq) } catch {
              case NonFatal(ex) =>
                observer.onError(ex)
            }
        })

      startFeedLoop(seq)
    }

  /**
   * Creates an Observable that emits the elements of the given ''iterable''.
   * Prefer [[fromSequence]] for immutable collections that can be efficiently decomposed as head/tail.
   *
   * <img src="https://raw.githubusercontent.com/wiki/alexandru/monifu/assets/rx-operators/fromIterable.png" />
   */
  def fromIterable[T](iterable: Iterable[T])(implicit scheduler: Scheduler): Observable[T] =
    fromIterable(iterable.asJava)

  /**
   * Creates an Observable that emits the elements of the given ''iterable''.
   * Prefer [[fromSequence]] for immutable collections that can be efficiently decomposed as head/tail.
   *
   * <img src="https://raw.githubusercontent.com/wiki/alexandru/monifu/assets/rx-operators/fromIterable.png" />
   */
  def fromIterable[T](iterable: java.lang.Iterable[T])(implicit scheduler: Scheduler): Observable[T] =
    Observable.create { o =>
      val observer = SafeObserver(o)

      def startFeedLoop(iterator: java.util.Iterator[T]): Unit =
        scheduler.execute(new Runnable {
          def run(): Unit =
            while (true) {
              try {
                if (iterator.hasNext) {
                  val elem = iterator.next()

                  observer.onNext(elem) match {
                    case Continue =>
                    // continue loop
                    case Done =>
                      return
                    case async =>
                      async.onSuccess {
                        case Continue =>
                          startFeedLoop(iterator)
                      }
                      return // interrupt the loop
                  }
                }
                else {
                  observer.onComplete()
                  return
                }
              }
              catch {
                case NonFatal(ex) =>
                  observer.onError(ex)
              }
            }
        })

      val iterator = iterable.iterator()
      startFeedLoop(iterator)
    }

  /**
   * Concatenates the given list of ''observables'' into a single observable.
   */
  def flatten[T](sources: Observable[T]*)(implicit scheduler: Scheduler): Observable[T] =
    Observable.fromSequence(sources).flatten

  /**
   * Merges the given list of ''observables'' into a single observable.
   */
  def merge[T](sources: Observable[T]*)(implicit scheduler: Scheduler): Observable[T] =
    Observable.fromSequence(sources).merge

  /**
   * Creates a new Observable from two observables,
   * by emitting elements combined in pairs. If one of the Observable emits fewer
   * events than the other, then the rest of the unpaired events are ignored.
   */
  def zip[T1, T2](obs1: Observable[T1], obs2: Observable[T2]): Observable[(T1,T2)] =
    obs1.zip(obs2)

  /**
   * Creates a new Observable from three observables,
   * by emitting elements combined in tuples of 3 elements. If one of the Observable emits fewer
   * events than the others, then the rest of the unpaired events are ignored.
   */
  def zip[T1, T2, T3](obs1: Observable[T1], obs2: Observable[T2], obs3: Observable[T3]): Observable[(T1, T2, T3)] =
    obs1.zip(obs2).zip(obs3).map { case ((t1, t2), t3) => (t1, t2, t3) }

  /**
   * Creates a new Observable from three observables,
   * by emitting elements combined in tuples of 4 elements. If one of the Observable emits fewer
   * events than the others, then the rest of the unpaired events are ignored.
   */
  def zip[T1, T2, T3, T4](obs1: Observable[T1], obs2: Observable[T2], obs3: Observable[T3], obs4: Observable[T4]): Observable[(T1, T2, T3, T4)] =
    obs1.zip(obs2).zip(obs3).zip(obs4).map { case (((t1, t2), t3), t4) => (t1, t2, t3, t4) }

  /**
   * Concatenates the given list of ''observables'' into a single observable.
   */
  def concat[T](sources: Observable[T]*)(implicit scheduler: Scheduler): Observable[T] =
    Observable.fromSequence(sources).concat

  /**
   * Implicit conversion from Future to Observable.
   */
  implicit def FutureIsObservable[T](future: Future[T])(implicit scheduler: Scheduler): Observable[T] =
    Observable.create { o =>
      val observer = SafeObserver(o)

      future.onComplete {
        case Success(value) =>
          observer.onNext(value).onSuccess {
            case Continue =>
              observer.onComplete()
          }
        case Failure(ex) =>
          observer.onError(ex)
      }
    }
}
