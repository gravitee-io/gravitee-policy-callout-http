You can use the `callout-http` policy to invoke an HTTP(S) URL and place a subset, or all, of the content in
one or more variables of the request execution context.

This can be useful if you need some data from an external service and want to inject it during request
processing.

The result of the callout is placed in a variable called `calloutResponse` and is only available during policy
execution. If no variable is configured the result of the callout is no longer available.

