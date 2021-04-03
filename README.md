[![GitHub CI](https://github.com/martinandersson/nomagichttp/actions/workflows/build.yml/badge.svg)](https://github.com/martinandersson/nomagichttp/actions/workflows/build.yml)
[![JitPack](https://jitpack.io/v/martinandersson/nomagichttp.svg)](https://jitpack.io/#martinandersson/nomagichttp)

# NoMagicHTTP

**A server-side Java library used to receive HTTP requests and
respond to them.**

The NoMagicHTTP server is natively asynchronous and doesn't even use event
polling or selector threads. What you get is an _elegant_ and modern API that is
just about as fast and scalable as any cross-platform JDK-based HTTP server
implementation could possibly be.

Best of all, the API design is based on the firmly held principle that all forms
of magic are evil. Annotations, "beans" and God-like "context" objects will
never be a part of the library. Top priority is killer JavaDocs, developer joy,
and no waste of time. The only way to deliver on this promise was to build the
server from scratch, using 100% Java.

[JavaDoc is available.][0-1]

**WARNING:** This project is fresh out of the oven without proper release
management in place and likely not very useful at the moment. The document
[POA.md][0-2] details planned future work- and consequently, what parts of the
HTTP stack have not yet been delivered.

[0-1]: https://jitpack.io/com/github/martinandersson/nomagichttp/-SNAPSHOT/javadoc/
[0-2]: POA.md

## Minimal Example

In an empty directory, create a new [Gradle][1-1] build file `build.gradle`:

    plugins {
        id 'application'
    }
    
    repositories {
        maven { url 'https://jitpack.io' }
    }
    
    dependencies {
        implementation 'com.github.martinandersson:nomagichttp:master-SNAPSHOT'
    }
    
    application {
        mainClass = 'Greeter'
    }

In subfolder `src/main/java`, create a new file `Greeter.java`:

    import alpha.nomagichttp.HttpServer;
    import static alpha.nomagichttp.handler.RequestHandlers.GET;
    import static alpha.nomagichttp.message.Responses.text;
    
    class Greeter {
        public static void main(String[] args) throws java.io.IOException {
            HttpServer.create()
                      .add("/greeting", GET().respond(text("Hello Stranger!")))
                      .start(8080);
        }
    }

Make sure you are using Java 11+, then start the server:

```console
foo@bar:~$ gradle run
> Task :run
Mar 03, 2021 7:29:26 AM alpha.nomagichttp.internal.DefaultServer start
INFO: Opened server channel: sun.nio.ch.UnixAsynchronousServerSocketChannelImpl[/0:0:0:0:0:0:0:0:8080]
<=========----> 75% EXECUTING [1s]
> :run
```

In another terminal:

```console
foo@bar:~$  curl localhost:8080/greeting
Hello Stranger!
```

We just started the app using Gradle. Even so, the start-up time is negligible.
In a real-world scenario where Java is used directly, the start-up time is
pretty much instantaneous. Be prepared for uber-fast development cycles and
running real HTTP exchanges in your test cases, _because you can_.

[1-1]: https://docs.gradle.org/current/userguide/tutorial_using_tasks.html

## Getting started

The intent of this project is to be primarily documented through [JavaDoc][0-1]
of an API that is _discoverable_ and intuitive.

The examples provided in subsequent sections are packaged with the published JAR
file and can be executed simply by replacing the `mainClass` value in the
previous Gradle build file. I.e., to run the first example, do:

    application {
        mainClass = 'alpha.nomagichttp.examples.HelloWorld'
    }

For brevity, the example runs provided does not use Gradle and assume that the
JAR file path and package have been provided by two environment variables
instead.

It is recommended to follow the links in each example as the source code
contains useful commentary that explains the API used.

[2-1]: src/main/java/alpha/nomagichttp/package-info.java

### Selecting port

Commonly, the server port is provided through an environment variable or
command-line argument. If a port is not specified, the system will pick a port
on the loopback address.

See code: [src/main/java/.../HelloWorld.java][3-1]

Run:

```console
foo@bar:~$ java --class-path=$JAR $PKG.HelloWorld
Listening on port 52063.
```

Make a request to the port in a new terminal window:

```console
foo@bar:~$ curl -i localhost:52063/hello
HTTP/1.1 200 OK
Content-Type: text/plain; charset=utf-8
Content-Length: 12

Hello World!
```

[3-1]: src/main/java/alpha/nomagichttp/examples/HelloWorld.java

### Greet using name from request path

This example registers two routes in order to respond a greeting with a name
taken from a path- or query parameter.

See code: [src/main/java/.../GreetParameter.java][4-1]

Run:

```console
foo@bar:~$ java --class-path=$JAR $PKG.GreetParameter
Listening on port 8080.
```

In a new terminal, run:

```console
foo@bar:~$ curl -i localhost:8080/hello/John
HTTP/1.1 200 OK
Content-Type: text/plain; charset=utf-8
Content-Length: 11

Hello John!
```

Alternatively, you may pass the name as a query parameter:

```console
foo@bar:~$ curl -i localhost:8080/hello?name=John
```

[4-1]: src/main/java/alpha/nomagichttp/examples/GreetParameter.java

### Greet using name from request body

This example will greet the user with a name taken as being the request body.

See code: [src/main/java/.../GreetBody.java][5-1]

Run:

```console
foo@bar:~$ java --class-path=$JAR $PKG.GreetBody
Listening on port 8080.
```

In a new terminal, run:

```console
foo@bar:~$ curl -i localhost:8080/hello -d John
HTTP/1.1 200 OK
Content-Type: text/plain; charset=utf-8
Content-Length: 11

Hello John!
```

[5-1]: src/main/java/alpha/nomagichttp/examples/GreetBody.java

### Echo request headers

This example echoes back the request headers.

See code: [src/main/java/.../EchoHeaders.java][6-1]

Run:

```console
foo@bar:~$ java --class-path=$JAR $PKG.EchoHeaders
Listening on port 8080.
```

In a new terminal, run:

```console
foo@bar:~$ curl -i localhost:8080/echo \
    -H "My-Header: Value 1" \
    -H "My-Header: Value 2"
HTTP/1.1 204 No Content
Accept: */*
Host: localhost:8080
My-Header: Value 1
My-Header: Value 2
User-Agent: curl/7.68.0
```

[6-1]: src/main/java/alpha/nomagichttp/examples/EchoHeaders.java

### Retry request on error

This example demonstrates error handling and will re-execute the request handler
on a particular known exception.

See code: [src/main/java/.../RetryRequestOnError.java][7-1]

Run:

```console
foo@bar:~$ java --class-path=$JAR $PKG.RetryRequestOnError
Listening on port 8080.
```

In a new terminal, run:

```console
foo@bar:~$ curl -i localhost:8080
HTTP/1.1 204 No Content
```

In the server terminal, you should see text similar to this:
```console
Request handler received a request 15:19:58.780 and will crash!
Error handler will retry #1 after delay (ms): 40
Request handler received a request 15:19:58.827 and will return 204 No Content
```

[7-1]: src/main/java/alpha/nomagichttp/examples/RetryRequestOnError.java
