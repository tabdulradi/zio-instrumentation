# zio-instrumentation
Allows instrumenting IO applications, mainly via OpenTracing integration, but also capable of integration with not-opentracing-but-close-enough tools like AWS X-Ray.

## Project Goals

- Provide ability to manually instrument ZIO applications (Must)
- Support multiple vendors  (Must)
- Integrates with manually instrumented Cats effect libraries (Nice to have)
- Provide ability to autimatically instrument ZIO applications (Nice to have)

## How to run examples

1. Run Jaeger (or any other Open Tracing Backend)
```bash
docker run -d --name jaeger \
  -e COLLECTOR_ZIPKIN_HTTP_PORT=9411 \
  -p 5775:5775/udp \
  -p 6831:6831/udp \
  -p 6832:6832/udp \
  -p 5778:5778 \
  -p 16686:16686 \
  -p 14268:14268 \
  -p 9411:9411 \
  jaegertracing/all-in-one:1.8
```

2. Run example app
```bash
sbt run
```

3. Navigate to [localhost:16686](http://localhost:16686), select "example" (from the first drop down), then click find traces.
