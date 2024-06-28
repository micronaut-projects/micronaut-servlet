## Plain Old Java Application (POJA) using Micronaut HTTP Framework

`micronaut-http-poja` module provides an implementation of the Micronaut HTTP Framework for Plain Old Java Applications (POJA).
Such applications can be integrated with Server frameworks such as Unix Super Server (aka Inetd).

## Sample Application

This is sample showing an example of using the HTTP POJA module (`micronaut-http-poja`) for serverless applications.

## Tests

The tests have `micronaut-http-poja-test` dependency that simplifies the implementation

## Running

To run this sample use:
```shell
gradle :micronaut-test-sample-poja:run --console=plain
```

Then provide the request in Standard input of the console:
```shell
GET / HTTP/1.1
Host: h


```

Get the response:
```shell
HTTP/1.1 200 Ok
Date: Thu, 27 Jun 2024 20:31:09 GMT
Content-Type: text/plain
Content-Length: 32

Hello, Micronaut Without Netty!

```

