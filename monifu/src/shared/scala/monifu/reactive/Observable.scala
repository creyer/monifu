package monifu.reactive

import language.implicitConversions
import monifu.concurrent.{Scheduler, Cancelable}
import scala.concurrent.{Promise, Future}
import scala.concurrent.Future.successful
import monifu.reactive.api._
import Ack.{Done, Continue}
import monifu.concurrent.atomic.padded.Atomic
import monifu.concurrent.cancelables._
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal
import scala.collection.mutable
import scala.annotation.tailrec
import collection.JavaConverters._
import scala.util.Failure
import scala.Some
import scala.util.Success
import monifu.reactive.subjects.{PublishSubject, Subject}


/**
 * Asynchronous implementation of the Observable interface
 */
trait Observable[+T] {
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
  def subscribe(observer: Observer[T]): Cancelable

  /**
   * Implicit scheduler required for asynchronous boundaries.
   */
  protected implicit def scheduler: Scheduler

  /**
   * Helper to be used by consumers for subscribing to an observable.
   */
  def subscribeUnit(nextFn: T => Unit, errorFn: Throwable => Unit, completedFn: () => Unit): Cancelable =
    subscribe(new Observer[T] {
      def onNext(elem: T): Future[Ack] =
        try {
          nextFn(elem)
          Continue
        }
        catch {
          case NonFatal(ex) =>
            onError(ex)
        }

      def onError(ex: Throwable) =
        try {
          errorFn(ex)
          Done
        }
        catch {
          case NonFatal(e) =>
            Future.failed(e)
        }

      def onCompleted() =
        try {
          completedFn()
          Done
        }
        catch {
          case NonFatal(ex) =>
            onError(ex)
        }
    })

  /**
   * Helper to be used by consumers for subscribing to an observable.
   */
  def subscribeUnit(nextFn: T => Unit, errorFn: Throwable => Unit): Cancelable =
    subscribeUnit(nextFn, errorFn, () => ())

  /**
   * Helper to be used by consumers for subscribing to an observable.
   */
  def subscribeUnit(nextFn: T => Unit): Cancelable =
    subscribeUnit(nextFn, error => error.printStackTrace(), () => ())

  /**
   * Returns an Observable that applies the given function to each item emitted by an
   * Observable and emits the result.
   *
   * @param f a function to apply to each item emitted by the Observable
   * @return an Observable that emits the items from the source Observable, transformed by the given function
   */
  def map[U](f: T => U): Observable[U] =
    Observable.create { observer =>
      subscribe(new Observer[T] {
        def onNext(elem: T) = {
          // See Section 6.4. in the Rx Design Guidelines:
          // Protect calls to user code from within an operator
          var streamError = true
          try {
            val u = f(elem)
            streamError = false
            observer.onNext(u)
          }
          catch {
            case NonFatal(ex) =>
              if (streamError)
                onError(ex)
              else
                Future.failed(ex)
          }
        }

        def onError(ex: Throwable) =
          observer.onError(ex)

        def onCompleted() =
          observer.onCompleted()
      })
    }

  /**
   * Returns an Observable which only emits those items for which the given predicate holds.
   *
   * @param p a function that evaluates the items emitted by the source Observable, returning `true` if they pass the filter
   * @return an Observable that emits only those items in the original Observable for which the filter evaluates as `true`
   */
  def filter(p: T => Boolean): Observable[T] =
    Observable.create { observer =>
      subscribe(new Observer[T] {
        def onNext(elem: T) = {
          // See Section 6.4. in the Rx Design Guidelines:
          // Protect calls to user code from within an operator
          var streamError = true
          try {
            val isValid = p(elem)
            streamError = false
            if (isValid)
              observer.onNext(elem)
            else
              Continue
          }
          catch {
            case NonFatal(ex) =>
              if (streamError) onError(ex) else Future.failed(ex)
          }
        }

        def onError(ex: Throwable) =
          observer.onError(ex)

        def onCompleted() =
          observer.onCompleted()
      })
    }

  def foreach(cb: T => Unit): Unit =
    subscribe(new Observer[T] {
      def onNext(elem: T) =
        try { cb(elem); Continue } catch {
          case NonFatal(ex) =>
            onError(ex)
        }

      def onCompleted() = Done
      def onError(ex: Throwable) = {
        ex.printStackTrace()
        Done
      }
    })

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
  def flatMap[U](f: T => Observable[U]): Observable[U] =
    map(f).flatten

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
  def flatten[U](implicit ev: T <:< Observable[U]): Observable[U] =
    Observable.create { observerU =>
    // aggregate subscription that cancels everything
      val composite = CompositeCancelable()

      // we need to do ref-counting for triggering `EOF` on our observeU
      // when all the children threads have ended
      val finalCompletedPromise = Promise[Done]()
      val refCounter = RefCountCancelable {
        finalCompletedPromise.completeWith(observerU.onCompleted())
      }

      composite += subscribe(new Observer[T] {
        def onNext(childObservable: T) = {
          val upstreamPromise = Promise[Ack]()

          val refID = refCounter.acquireCancelable()
          val sub = SingleAssignmentCancelable()
          composite += sub

          sub := childObservable.subscribe(new Observer[U] {
            def onNext(elem: U) =
              observerU.onNext(elem)

            def onError(ex: Throwable) = {
              // error happened, so signaling both the main thread that it should stop
              // and the downstream consumer of the error
              val f = observerU.onError(ex)
              upstreamPromise.completeWith(f.map(_ => Done))
              f
            }

            def onCompleted() = Future {
              // removing the child subscription as we can have a leak otherwise
              composite -= sub
              // NOTE: we aren't sending this onCompleted signal downstream to our observerU
              // instead this will eventually send the EOF downstream (reference counting FTW)
              refID.cancel()
              // end of child observable, so signal main thread that it should continue
              upstreamPromise.success(Continue)
              Done
            }
          })

          upstreamPromise.future
        }

        def onError(ex: Throwable) = {
          // oops, error happened on main thread, piping that along should cancel everything
          observerU.onError(ex)
        }

        def onCompleted() = {
          // initiating the `observeU(EOF)` process by counting down on the remaining children
          refCounter.cancel()
          finalCompletedPromise.future
        }
      })

      composite
    }

  /**
   * Selects the first ''n'' elements (from the start).
   *
   *  @param  n  the number of elements to take
   *  @return    a new Observable that emits only the first ''n'' elements from the source
   */
  def take(n: Long): Observable[T] =
    Observable.create { observer =>
      val counterRef = Atomic(0L)

      subscribe(new Observer[T] {
        def onNext(elem: T) = {
          // short-circuit for not endlessly incrementing that number
          if (counterRef.get < n) {
            // this increment needs to be synchronized - a well behaved producer
            // does back-pressure by means of the acknowledgement that the observer
            // returns, however we can still have visibility problems
            val counter = counterRef.incrementAndGet()

            if (counter < n) {
              // this is not the last event in the stream, so send it directly
              observer.onNext(elem)
            }
            else if (counter == n) {
              // last event in the stream, so we need to send the event followed by an EOF downstream
              // after which we signal upstream to the producer that it should stop
              observer.onNext(elem).flatMap { _ =>
                observer.onCompleted()
              }
            }
            else {
              // we already emitted the maximum number of events, so signal upstream
              // to the producer that it should stop sending events
              successful(Done)
            }
          }
          else {
            // we already emitted the maximum number of events, so signal upstream
            // to the producer that it should stop sending events
            successful(Done)
          }
        }

        def onError(ex: Throwable) =
          observer.onError(ex)

        def onCompleted() =
          observer.onCompleted()
      })
    }

  /**
   * Drops the first ''n'' elements (from the start).
   *
   *  @param  n  the number of elements to drop
   *  @return    a new Observable that drops the first ''n'' elements
   *             emitted by the source
   */
  def drop(n: Long): Observable[T] =
    Observable.create { observer =>
      val count = Atomic(0L)

      subscribe(new Observer[T] {
        def onNext(elem: T) = {
          if (count.get < n && count.getAndIncrement() < n)
            Continue
          else
            observer.onNext(elem)
        }

        def onCompleted() =
          observer.onCompleted()

        def onError(ex: Throwable) =
          observer.onError(ex)
      })
    }

  /**
   * Takes longest prefix of elements that satisfy the given predicate
   * and returns a new Observable that emits those elements.
   */
  def takeWhile(p: T => Boolean): Observable[T] =
    Observable.create { observer =>
      subscribe(new Observer[T] {
        @volatile var shouldContinue = true

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
                observer.onCompleted()
              }
            }
            catch {
              case NonFatal(ex) =>
                if (streamError) onError(ex) else Future.failed(ex)
            }
          }
          else
            Done
        }

        def onCompleted() =
          observer.onCompleted()

        def onError(ex: Throwable) =
          observer.onError(ex)
      })
    }

  /**
   * Drops the longest prefix of elements that satisfy the given predicate
   * and returns a new Observable that emits the rest.
   */
  def dropWhile(p: T => Boolean): Observable[T] =
    Observable.create { observer =>
      subscribe(new Observer[T] {
        @volatile var shouldDrop = true

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
                if (streamError) onError(ex) else Future.failed(ex)
            }
          }
          else
            observer.onNext(elem)
        }

        def onCompleted() =
          observer.onCompleted()

        def onError(ex: Throwable) =
          observer.onError(ex)
      })
    }

  /**
   * Applies a binary operator to a start value and all elements of this Observable,
   * going left to right and returns a new Observable that emits only one item
   * before `onCompleted`.
   */
  def foldLeft[R](initial: R)(op: (R, T) => R): Observable[R] =
    Observable.create { observer =>
      val state = Atomic(initial)

      subscribe(new Observer[T] {
        def onNext(elem: T): Future[Ack] = {
          // See Section 6.4. in the Rx Design Guidelines:
          // Protect calls to user code from within an operator
          try {
            state.transformAndGet(s => op(s, elem))
            Continue
          }
          catch {
            case NonFatal(ex) => onError(ex)
          }
        }

        def onCompleted() =
          observer.onNext(state.get).flatMap { _ =>
            observer.onCompleted()
          }

        def onError(ex: Throwable) =
          observer.onError(ex)
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
  def scan[R](initial: R)(op: (R, T) => R): Observable[R] =
    Observable.create { observer =>
      val state = Atomic(initial)

      subscribe(new Observer[T] {
        def onNext(elem: T): Future[Ack] = {
          // See Section 6.4. in the Rx Design Guidelines:
          // Protect calls to user code from within an operator
          var streamError = true
          try {
            val result = state.transformAndGet(s => op(s, elem))
            streamError = false
            observer.onNext(result)
          }
          catch {
            case NonFatal(ex) =>
              if (streamError) onError(ex) else Future.failed(ex)
          }
        }

        def onCompleted() =
          observer.onCompleted()

        def onError(ex: Throwable) =
          observer.onError(ex)
      })
    }

  /**
   * Executes the given callback when the stream has ended on `onCompleted`
   *
   * NOTE: protect the callback such that it doesn't throw exceptions, because
   * it gets executed when `cancel()` happens and by definition the error cannot
   * be streamed with `onError()` and so the behavior is left as undefined, possibly
   * crashing the application or worse - leading to non-deterministic behavior.
   *
   * @param cb the callback to execute when the subscription is canceled
   */
  def doOnCompleted(cb: => Unit): Observable[T] =
    Observable.create { observer =>
      subscribe(new Observer[T] {
        def onNext(elem: T) =
          observer.onNext(elem)

        def onError(ex: Throwable) =
          observer.onError(ex)

        def onCompleted() = {
          var streamError = true
          try {
            cb
            streamError = false
            observer.onCompleted()
          }
          catch {
            case NonFatal(ex) =>
              if (streamError) observer.onError(ex) else Future.failed(ex)
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
  def doWork(cb: T => Unit): Observable[T] =
    Observable.create { observer =>
      subscribe(new Observer[T] {
        def onError(ex: Throwable) = observer.onError(ex)
        def onCompleted() = observer.onCompleted()

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
              if (streamError) onError(ex) else Future.failed(ex)
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
  def find(p: T => Boolean): Observable[T] =
    filter(p).head

  /**
   * Returns an Observable which emits a single value, either true, in case the given predicate holds for at least
   * one item, or false otherwise.
   *
   * @param p a function that evaluates the items emitted by the source Observable, returning `true` if they pass the filter
   * @return an Observable that emits only true or false in case the given predicate holds or not for at least one item
   */
  def exists(p: T => Boolean): Observable[Boolean] =
    find(p).foldLeft(false)((_, _) => true)

  /**
   * Returns an Observable that emits a single boolean, either true, in case the given predicate holds for all the items
   * emitted by the source, or false in case at least one item is not verifying the given predicate.
   *
   * @param p a function that evaluates the items emitted by the source Observable, returning `true` if they pass the filter
   * @return an Observable that emits only true or false in case the given predicate holds or not for all the items
   */
  def forAll(p: T => Boolean): Observable[Boolean] =
    exists(e => !p(e)).map(r => !r)

  /**
   * Returns the first generated result as a Future and then cancels
   * the subscription.
   */
  def asFuture: Future[Option[T]] = {
    val promise = Promise[Option[T]]()

    head.subscribe(new Observer[T] {
      def onNext(elem: T) = {
        promise.trySuccess(Some(elem))
        successful(Done)
      }

      def onCompleted() = {
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
  def ++[U >: T](other: => Observable[U]): Observable[U] =
    Observable.fromSequence(Seq(this, other)).flatten

  /**
   * Only emits the first element emitted by the source observable, after which it's completed immediately.
   */
  def head: Observable[T] = take(1)

  /**
   * Drops the first element of the source observable, emitting the rest.
   */
  def tail: Observable[T] = drop(1)

  /**
   * Emits the first element emitted by the source, or otherwise if the source is completed without
   * emitting anything, then the `default` is emitted.
   */
  def headOrElse[B >: T](default: => B): Observable[B] =
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
  def firstOrElse[U >: T](default: => U): Observable[U] =
    headOrElse(default)

  /**
   * Creates a new Observable from this Observable and another given Observable,
   * by emitting elements combined in pairs. If one of the Observable emits fewer
   * events than the other, then the rest of the unpaired events are ignored.
   */
  def zip[U](other: Observable[U]): Observable[(T, U)] =
    Observable.create { observerOfPairs =>
      val composite = CompositeCancelable()
      val lock = new AnyRef

      val queueA = mutable.Queue.empty[(Promise[U], Promise[Ack])]
      val queueB = mutable.Queue.empty[(U, Promise[Ack])]

      val completedPromise = Promise[Done]()
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

      composite += subscribe(new Observer[T] {
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

        def onCompleted() = lock.synchronized {
          if (!isCompleted && queueA.isEmpty) {
            isCompleted = true
            queueA.clear()
            queueB.clear()
            completedPromise.completeWith(observerOfPairs.onCompleted())
          }

          completedPromise.future
        }
      })

      composite += other.subscribe(new Observer[U] {
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

        def onCompleted() = lock.synchronized {
          if (!isCompleted && queueB.isEmpty) {
            isCompleted = true
            queueA.clear()
            queueB.clear()
            completedPromise.completeWith(observerOfPairs.onCompleted())
          }

          completedPromise.future
        }
      })

      composite
    }

  /**
   * Returns a new Observable that uses the specified `ExecutionContext` for listening to the emitted items.
   */
  def observeOn(s: Scheduler): Observable[T] = {
    implicit val scheduler = s

    Observable.create { observer =>
      subscribe(new Observer[T] {
        def onNext(elem: T): Future[Ack] = {
          val p = Promise[Ack]()
          scheduler.execute(new Runnable {
            def run(): Unit =
              p.completeWith(observer.onNext(elem))
          })
          p.future
        }

        def onError(ex: Throwable): Future[Done] = {
          val p = Promise[Done]()
          scheduler.execute(new Runnable {
            def run(): Unit =
              p.completeWith(observer.onError(ex))
          })
          p.future
        }

        def onCompleted(): Future[Done] = {
          val p = Promise[Done]()
          scheduler.execute(new Runnable {
            def run(): Unit =
              p.completeWith(observer.onCompleted())
          })
          p.future
        }
      })
    }
  }

  /**
   * Returns a new Observable that uses the specified `ExecutionContext` for initiating the subscription.
   */
  def subscribeOn(s: Scheduler): Observable[T] = {
    implicit val scheduler = s
    Observable.create(o => s.schedule(s => subscribe(o)))
  }

  def multicast[U >: T](subject: Subject[U] = PublishSubject[U]()): ConnectableObservable[U] =
    ConnectableObservable(this, subject, implicitly[Scheduler])
}

object Observable {
  /**
   * Observable constructor. To be used for implementing new Observables and operators.
   */
  def create[T](f: Observer[T] => Cancelable)(implicit scheduler: Scheduler): Observable[T] = {
    val s = scheduler
    new Observable[T] {
      val scheduler = s
      def subscribe(observer: Observer[T]): Cancelable =
        try f(observer) catch {
          case NonFatal(ex) =>
            observer.onError(ex)
            Cancelable.empty
        }
    }
  }

  def empty[A](implicit scheduler: Scheduler): Observable[A] =
    Observable.create { observer =>
      observer.onCompleted()
      Cancelable.empty
    }

  /**
   * Creates an Observable that only emits the given ''a''
   */
  def unit[A](elem: A)(implicit scheduler: Scheduler): Observable[A] = {
    Observable.create { observer =>
      val sub = BooleanCancelable()
      observer.onNext(elem).onSuccess {
        case Continue =>
          if (!sub.isCanceled)
            observer.onCompleted()
        case _ =>
          // nothing
      }
      sub
    }
  }


  /**
   * Creates an Observable that emits an error.
   */
  def error(ex: Throwable)(implicit scheduler: Scheduler): Observable[Nothing] =
    Observable.create { observer =>
      observer.onError(ex)
      Cancelable.empty
    }

  /**
   * Creates an Observable that doesn't emit anything and that never completes.
   */
  def never(implicit scheduler: Scheduler): Observable[Nothing] =
    Observable.create { _ => Cancelable() }

  /**
   * Creates an Observable that emits auto-incremented natural numbers with a fixed delay,
   * starting from number 1.
   *
   * @param period the delay between two emitted events
   * @param scheduler the execution context in which `onNext` will get called
   */
  def interval(period: FiniteDuration)(implicit scheduler: Scheduler): Observable[Long] =
    interval(period, period)

  /**
   * Creates an Observable that emits auto-incremented natural numbers with a fixed delay,
   * starting from number 1.
   *
   * @param initialDelay the initial delay to wait before the first emitted number
   * @param period the delay between two subsequent events
   * @param scheduler the execution context in which `onNext` will get called
   */
  def interval(initialDelay: FiniteDuration, period: FiniteDuration)(implicit scheduler: Scheduler): Observable[Long] = {
    Observable.create { observer =>
      val counter = Atomic(0)
      val sub = SingleAssignmentCancelable()

      sub := scheduler.scheduleRecursive(initialDelay, period, { reschedule =>
        observer.onNext(counter.incrementAndGet()) foreach {
          case Continue =>
            reschedule()
          case Done =>
            sub.cancel()
        }
      })

      sub
    }
  }

  /**
   * Creates an Observable that continuously emits the given ''item''
   */
  def continuous[T](elem: T)(implicit scheduler: Scheduler): Observable[T] =
    Observable.create { observer =>
      def loop(sub: BooleanCancelable, elem: T): Unit = {
        scheduler.execute(new Runnable {
          def run(): Unit =
            if (!sub.isCanceled)
              observer.onNext(elem).onSuccess {
                case Done => // do nothing
                case Continue if !sub.isCanceled =>
                  loop(sub, elem)
              }
        })
      }

      val sub = BooleanCancelable()
      loop(sub, elem)
      sub
    }

  /**
   * Creates an Observable that emits the elements of the given ''sequence''
   */
  def fromSequence[T](seq: Seq[T])(implicit scheduler: Scheduler): Observable[T] =
    Observable.create { observer =>
      def startFeedLoop(subscription: BooleanCancelable, seq: Seq[T]): Unit =
        scheduler.execute(new Runnable {
          private[this] var streamError = true

          @tailrec
          def loop(seq: Seq[T]): Unit = {
            if (seq.nonEmpty) {
              val elem = seq.head
              val tail = seq.tail
              streamError = false

              val ack = observer.onNext(elem)
              if (ack ne Continue) {
                if (ack ne Done)
                  ack.onSuccess {
                    case Continue =>
                      startFeedLoop(subscription, tail)
                    case Done =>
                      // Do nothing else
                  }
              }
              else
                loop(tail)
            }
            else {
              streamError = false
              observer.onCompleted()
            }
          }

          def run(): Unit =
            try {
              streamError = true
              loop(seq)
            }
            catch {
              case NonFatal(ex) =>
                if (streamError) observer.onError(ex) else throw ex
            }
        })

      val subscription = BooleanCancelable()
      startFeedLoop(subscription, seq)
      subscription
    }

  def fromIterable[T](iterable: Iterable[T])(implicit scheduler: Scheduler): Observable[T] =
    fromIterable(iterable.asJava)

  /**
   * Creates an Observable that emits the elements of the given ''sequence''
   */
  def fromIterable[T](iterable: java.lang.Iterable[T])(implicit scheduler: Scheduler): Observable[T] =
    Observable.create { observer =>
      def startFeedLoop(subscription: BooleanCancelable, iterator: java.util.Iterator[T]): Unit =
        scheduler.execute(new Runnable {
          def run(): Unit = synchronized {
            while (true) {
              var streamError = true
              try {
                if (iterator.hasNext) {
                  val elem = iterator.next()
                  streamError = false

                  val ack = observer.onNext(elem)
                  if (ack ne Continue) {
                    if (ack ne Done)
                      ack.onSuccess {
                        case Continue =>
                          startFeedLoop(subscription, iterator)
                        case Done =>
                          // do nothing else
                      }
                    return
                  }
                }
                else {
                  streamError = false
                  observer.onCompleted()
                  return
                }
              }
              catch {
                case NonFatal(ex) =>
                  if (streamError) {
                    observer.onError(ex)
                    return
                  }
                  else
                    throw ex
              }
            }
          }
        })

      val iterator = iterable.iterator()
      val subscription = BooleanCancelable()
      startFeedLoop(subscription, iterator)
      subscription
    }

  /**
   * Merges the given list of ''observables'' into a single observable.
   */
  def flatten[T](sources: Observable[T]*)(implicit scheduler: Scheduler): Observable[T] =
    Observable.fromSequence(sources).flatten

  implicit def FutureIsAsyncObservable[T](future: Future[T])(implicit scheduler: Scheduler): Observable[T] =
    Observable.create { observer =>
      val sub = BooleanCancelable()
      future.onComplete {
        case Success(value) if !sub.isCanceled =>
          observer.onNext(value).onSuccess {
            case Continue => observer.onCompleted()
          }
        case Failure(ex) if !sub.isCanceled =>
          observer.onError(ex)
        case _ =>
          // do nothing
      }
      sub
    }
}
