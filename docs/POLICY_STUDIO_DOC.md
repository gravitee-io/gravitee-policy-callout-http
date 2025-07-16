## Overview
You can use the `callout-http` policy to invoke an HTTP(S) URL and place a subset, or all, of the content in
one or more variables of the request execution context.

This can be useful if you need some data from an external service and want to inject it during request
processing.

The result of the callout is placed in a variable called `calloutResponse` and is only available during policy
execution. If no variable is configured the result of the callout is no longer available.

The CalloutHttpPolicy includes comprehensive OpenTelemetry tracing support using the V4 API, allowing you to monitor and debug HTTP callout operations with detailed span information.
The tracing integration is automatically enabled when OpenTelemetry tracing is configured in your Gravitee environment and enabled in API context.



## Errors
These templates are defined at the API level, in the "Entrypoint" section for v4 APIs, or in "Response Templates" for v2 APIs.
The error keys sent by this policy are as follows:

| Key |
| ---  |
| CALLOUT_EXIT_ON_ERROR |
| CALLOUT_HTTP_ERROR |


