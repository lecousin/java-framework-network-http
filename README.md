# lecousin.net - Java network framework - HTTP

The http module provides base implementations for a HTTP client and a HTTP server.

The HTTP client allows to connect to a HTTP server, send requests, and receive the response asynchronously.

The HTTPServerProtocol class implements the HTTP protocol, and gives the requests to process to a HTTPRequestProcessor that must be provided. It implements also the HTTP protocol upgrade mechanism.

The WebSocketServerProtocol implement the Web-Socket protocol on server side and can be used with the HTTP protocol upgrade mechanism.
The WebSocketClient allows to connect, receive messages and send messages using the Web-Socket protocol.

## Build status

### Current version - branch master

[![Maven Central](https://img.shields.io/maven-central/v/net.lecousin.framework.network/http.svg)](http://search.maven.org/#search%7Cga%7C1%7Cg%3A%22net.lecousin.framework.network%22%20AND%20a%3A%22http%22)
[![Javadoc](https://img.shields.io/badge/javadoc-0.3.1-brightgreen.svg)](https://www.javadoc.io/doc/net.lecousin.framework.network/http/0.3.1)

![build status](https://travis-ci.org/lecousin/java-framework-network-http.svg?branch=master "Build Status")
![build status](https://ci.appveyor.com/api/projects/status/github/lecousin/java-framework-network-http?branch=master&svg=true "Build Status")
[![Codecov](https://codecov.io/gh/lecousin/java-framework-network-http/graph/badge.svg)](https://codecov.io/gh/lecousin/java-framework-network-http/branch/master)

### Next minor release - branch 0.3

![build status](https://travis-ci.org/lecousin/java-framework-network-http.svg?branch=0.3 "Build Status")
![build status](https://ci.appveyor.com/api/projects/status/github/lecousin/java-framework-network-http?branch=0.3&svg=true "Build Status")
[![Codecov](https://codecov.io/gh/lecousin/java-framework-network-http/branch/0.3/graph/badge.svg)](https://codecov.io/gh/lecousin/java-framework-network-http/branch/0.3)