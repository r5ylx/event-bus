# event-bus

A small event bus for Java, with no runtime dependencies. Subscribe with an annotation or with
a lambda; both go through the same dispatch.

`com.r5ylx:event-bus:1.0.0` - Java 21 or newer, Apache 2.0.

## Install

Not on Maven Central yet. Clone and install it locally:

```bash
git clone https://github.com/r5ylx/event-bus.git
cd event-bus
./gradlew publishToMavenLocal
```

Then, in the consuming build:

```groovy
repositories {
    mavenLocal()
}

dependencies {
    implementation 'com.r5ylx:event-bus:1.0.0'
}
```

## Usage

```java
EventBus bus = new EventBus();
```

### Subscribing with an annotation

```java
class Renderer {
    @Subscribe(priority = EventPriority.HIGH)
    void onRender(RenderEvent event) {
        ...
    }
}

Renderer renderer = new Renderer();
bus.subscribe(renderer);
bus.unsubscribe(renderer);
```

Methods declared in a supertype or an interface are picked up too. Overriding one and annotating
it again still registers a single listener. `private` methods work.

An annotated method must take exactly one non-primitive parameter, return `void`, and be neither
`static` nor `abstract`. Anything else throws at `subscribe` time rather than being ignored.

### Subscribing with a lambda

```java
Subscription subscription = bus.subscribe(RenderEvent.class, event -> ...);
subscription.unsubscribe();

// Subscription is AutoCloseable
try (Subscription sub = bus.subscribe(TickEvent.class, this::onTick)) {
    ...
}
```

### Posting

```java
RenderEvent event = bus.post(new RenderEvent());
```

`post` returns the instance it was given, so you can read back a cancellation or anything a
listener wrote into the event.

### Cancelling

```java
class ChatEvent implements ICancellable { ... }

@Subscribe(priority = EventPriority.HIGHEST)
void onChat(ChatEvent event) {
    if (isSpam(event)) {
        event.cancel();
    }
}
```

Dispatch ends completely at that point. **Listeners registered on supertypes and interfaces are
not reached either.**

### Running once

```java
bus.subscribe(null, ReadyEvent.class, event -> ..., EventPriority.NORMAL, true);

@Subscribe(once = true)
void onReady(ReadyEvent event) { ... }
```

Even when several threads post at the same time, a one-shot listener runs exactly once.

### Suspending

```java
bus.deactivate(module);   // keep the registration, skip the calls
bus.activate(module);
```

## Dispatch rules

| | |
| --- | --- |
| Reach | the event's own type, every supertype, every interface |
| Order | **priority alone, regardless of type**. A `HIGHEST` on a supertype runs before a `LOW` on the concrete type |
| Ties | registration order |
| Cancelling | ends the dispatch completely |
| Exceptions | handed to `EventExceptionHandler`, which rethrows by default |

## Exceptions

By default an exception from a listener is wrapped in an `EventDispatchException` and thrown out
of `post`. Swallowing is not the default, because it produces the hardest kind of bug to find:
the event arrives and nothing happens.

When one failing listener should not stop the rest:

```java
EventBus bus = new EventBus(EventExceptionHandler.logging(LOGGER::error));

// or later
bus.setExceptionHandler((event, subscription, error) ->
    LOGGER.error("{} failed to handle the event", subscription, error));
```

An `Error` such as `OutOfMemoryError` is always propagated as it is, whichever handler is set.

## Checking for listeners

```java
if (bus.hasListeners(ExpensiveEvent.class)) {
    bus.post(buildExpensiveEvent());
}
```

`hasListeners` and `listenerCount` count registrations on supertypes too, so they agree with who
`post` would actually reach. Use them to skip building an expensive event.

## Thread safety

Every operation is thread-safe. `post` takes no lock once the dispatch table is warm.

Subscribing or unsubscribing during a dispatch has an unspecified effect on that dispatch. It
always takes effect from the next `post` onwards.

## Public API

| Type | Role |
| --- | --- |
| `EventBus` | posting and subscribing |
| `Subscription` | a handle to one subscription: unsubscribe, suspend |
| `EventPriority` | the usual priority steps; a raw int can be passed instead |
| `ICancellable` | implemented by an event that can be cancelled |
| `EventExceptionHandler` | where an exception from a listener goes |
| `EventDispatchException` | what the default handler throws |
| `@Subscribe` | marks a receiving method |

The listener implementations are not public; a subscription is handled through `Subscription`.
There are no subpackages - everything lives in `com.r5ylx.events`.

## Building

```bash
./gradlew build   # compile, javadoc, test
./gradlew test
```

`-Xlint:all -Werror` is on. A single warning fails the build.

## License

Apache License 2.0. See [LICENSE](LICENSE).
