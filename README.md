<img src="https://raw.githubusercontent.com/wiki/alexandru/monifu/assets/monifu.png" align="right" />

Extensions to Scala's standard library for multi-threading primitives and functional reactive programming. Targets both the JVM and [Scala.js](http://www.scala-js.org/).

[![Build Status](https://travis-ci.org/alexandru/monifu.png?branch=v0.11.0)](https://travis-ci.org/alexandru/monifu)

## Feature Overview

[Atomic References](https://github.com/alexandru/monifu/wiki/Atomic-References)

```scala
import monifu.concurrent.atomic.Atomic

val queue = Atomic(Queue.empty[String])

queue.transform(queue.enqueue("first item"))
queue.transform(queue.enqueue("second item"))

queue.transformAndExtract(queue.dequeue)
//=> "first item"

queue.transformAndExtract(queue.dequeue)
//=> "second item"

val number = Atomic(BigInt(1))

number.incrementAndGet
//=> res: scala.math.BigInt = 2
```

[Schedulers](https://github.com/alexandru/monifu/wiki/Schedulers)

```scala
import monifu.concurrent.atomic.Atomic
import monifu.concurrent.Scheduler.{computation => s}

val loop = Atomic(0) // we don't actually need an atomic or volatile here

s.scheduleRecursive(1.second, 5.seconds, { reschedule =>
  if (loop.incrementAndGet < 10) {
    println(s"Counted: $counted")
    // do next one
    reschedule()    
  }
})
```

[Reactive Extensions](https://github.com/alexandru/monifu/wiki/Reactive-Extensions-(Rx))

```scala
import monifu.concurrent.Scheduler.Implicits.global
import play.api.libs.ws._
import monifu.reactive._

// emits an auto-incremented number, every second
Observable.interval(1.second)
  // drops the first 10 emitted events
  .drop(10) 
  // takes the first 100 emitted events  
  .take(100) 
  // per second, makes requests and concatenates the results
  .flatMap(x => WS.request(s"http://some.endpoint.com/request?tick=$x").get())
  // filters only valid responses
  .filter(response => response.status == 200) 
  // processes response, selecting the body
  .map(response => response.body) 
  // creates subscription, foreach response print it
  .foreach(x => println(x)) 
```

## Documentation

The available documentation is maintained as a [GitHub's Wiki](https://github.com/alexandru/monifu/wiki).
Work in progress:

* [Atomic References](https://github.com/alexandru/monifu/wiki/Atomic-References) 
* [Schedulers](https://github.com/alexandru/monifu/wiki/Schedulers) and [Cancelables](https://github.com/alexandru/monifu/wiki/Cancelables)
* [Reactive Extensions (Rx)](https://github.com/alexandru/monifu/wiki/Reactive-Extensions-%28Rx%29)

API documentation:

* [monifu](http://www.monifu.org/monifu/current/api/)
* [monifu-js](http://www.monifu.org/monifu-js/current/api/)

Release Notes:

* [Version 0.11 - May 28, 2014](https://github.com/alexandru/monifu/wiki/0.11)
* [Version 0.10 - May 26, 2014](https://github.com/alexandru/monifu/wiki/0.10)
* [Version 0.9 - May 23, 2014](https://github.com/alexandru/monifu/wiki/0.9)
* [Version 0.8 - May 13, 2014](https://github.com/alexandru/monifu/wiki/0.8)
* [Version 0.7 - April 26, 2014](https://github.com/alexandru/monifu/wiki/0.7)
* [Other Releases](https://github.com/alexandru/monifu/wiki/Release-Notes)

## Usage

The packages are published on Maven Central.

Compiled for Scala 2.10 and Scala 2.11. Also cross-compiled to
the latest Scala.js (at the moment Scala.js 0.4.4). The targetted JDK version
for the published packages is version 6 (see 
[faq entry](https://github.com/alexandru/monifu/wiki/Frequently-Asked-Questions#what-javajdk-version-is-required)).

Current stable release is: `0.11.0`

### For the JVM

```scala
libraryDependencies += "org.monifu" %% "monifu" % "0.11.0"
```

### For targeting Javascript runtimes with Scala.js

```scala
libraryDependencies += "org.monifu" %% "monifu-js" % "0.11.0"
```

## License

All code in this repository is licensed under the Apache License, Version 2.0.
See [LICENCE.txt](./LICENSE.txt).
