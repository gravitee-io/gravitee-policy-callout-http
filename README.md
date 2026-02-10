
<!-- GENERATED CODE - DO NOT ALTER THIS OR THE FOLLOWING LINES -->
# HTTP Callout

[![Gravitee.io](https://img.shields.io/static/v1?label=Available%20at&message=Gravitee.io&color=1EC9D2)](https://download.gravitee.io/#graviteeio-apim/plugins/policies/gravitee-policy-policy-http-callout/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://github.com/gravitee-io/gravitee-policy-policy-http-callout/blob/master/LICENSE.txt)
[![Releases](https://img.shields.io/badge/semantic--release-conventional%20commits-e10079?logo=semantic-release)](https://github.com/gravitee-io/gravitee-policy-policy-http-callout/releases)
[![CircleCI](https://circleci.com/gh/gravitee-io/gravitee-policy-policy-http-callout.svg?style=svg)](https://circleci.com/gh/gravitee-io/gravitee-policy-policy-http-callout)

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



## Phases
The `policy-http-callout` policy can be applied to the following API types and flow phases.

### Compatible API types

* `PROXY`
* `MESSAGE`
* `NATIVE KAFKA`
* `MCP PROXY`
* `LLM PROXY`

### Supported flow phases:

* Request
* Response
* Publish
* Subscribe

## Compatibility matrix
Strikethrough text indicates that a version is deprecated.

| Plugin version| APIM |
| --- | ---  |
|5.x|4.6.x to latest |
|4.x|4.4.x to 4.5.x |
|3.x|4.0.x to 4.3.x |
|~~2.x~~|~~3.18.x to 3.20.x~~ |
|~~1.15.x and upper~~|~~3.15.x to 3.17.x~~ |
|~~1.13.x to 1.14.x~~|~~3.10.x to 3.14.x~~ |
|~~Up to 1.12.x~~|~~Up to 3.9.x~~ |



## Configuration
### Gateway configuration
### System proxy

If the option `useSystemProxy` is checked, proxy information will be read from `JVM_OPTS`, or from the `gravitee.yml` file if `JVM_OPTS` is not set.

For example: 

gravitee.yml
```YAML
system:
  proxy:
    type: HTTP      # HTTP, SOCK4, SOCK5
    host: localhost
    port: 3128
    username: user
    password: secret
```



### Configuration options


#### 
| Name <br>`json name`  | Type <br>`constraint`  | Mandatory  | Default  | Description  |
|:----------------------|:-----------------------|:----------:|:---------|:-------------|
| Request body<br>`body`| string|  | | |
| Error condition<br>`errorCondition`| string|  | `{#calloutResponse.status >= 400 and #calloutResponse.status <= 599}`| The condition which will be verified to end the request (supports EL).|
| Error response body<br>`errorContent`| string|  | | The body response of the error if the condition is true (supports EL).|
| Error status code<br>`errorStatusCode`| enum (string)|  | `500`| HTTP Status Code send to the consumer if the condition is true.<br>Values: `100` `101` `102` `200` `201` `202` `203` `204` `205` `206` `207` `300` `301` `302` `303` `304` `305` `307` `400` `401` `402` `403` `404` `405` `406` `407` `408` `409` `410` `411` `412` `413` `414` `415` `416` `417` `422` `423` `424` `429` `500` `501` `502` `503` `504` `505` `507`|
| Exit on error<br>`exitOnError`| boolean| ✅| | Terminate the request if the error condition is true.|
| Fire & forget<br>`fireAndForget`| boolean|  | | Make the HTTP call without expecting any response. When activating this mode, context variables and exit on error are useless.|
| Request Headers<br>`headers`| array|  | | <br/>See "Request Headers" section.|
| HTTP Method<br>`method`| enum (string)| ✅| `GET`| HTTP method to invoke the endpoint.<br>Values: `GET` `POST` `PUT` `DELETE` `PATCH` `HEAD` `CONNECT` `OPTIONS` `TRACE`|
| URL<br>`url`| string| ✅| | |
| Use system proxy<br>`useSystemProxy`| boolean|  | | Use the system proxy configured by your administrator.|
| Context variables<br>`variables`| array|  | | <br/>See "Context variables" section.|


#### Request Headers (Array)
| Name <br>`json name`  | Type <br>`constraint`  | Mandatory  | Description  |
|:----------------------|:-----------------------|:----------:|:-------------|
| Name<br>`name`| string|  | |
| Value<br>`value`| string|  | |


#### Context variables (Array)
| Name <br>`json name`  | Type <br>`constraint`  | Mandatory  | Default  | Description  |
|:----------------------|:-----------------------|:----------:|:---------|:-------------|
| Evaluate as string<br>`evaluateAsString`| boolean|  | `true`| Evaluate the value as a string. Default is true for backward compatibility.|
| Name<br>`name`| string|  | | |
| Value<br>`value`| string|  | `{#jsonPath(#calloutResponse.content, '$.field')}`| |




## Examples

*API with basic callout*
```json
{
  "api": {
    "definitionVersion": "V4",
    "type": "PROXY",
    "name": "HTTP Callout example API",
    "flows": [
      {
        "name": "Common Flow",
        "enabled": true,
        "selectors": [
          {
            "type": "HTTP",
            "path": "/",
            "pathOperator": "STARTS_WITH"
          }
        ],
        "request": [
          {
            "name": "HTTP Callout",
            "enabled": true,
            "policy": "policy-http-callout",
            "configuration":
              {
                  "method": "GET",
                  "url": "https://api.gravitee.io/echo",
                  "headers": [
                      {
                          "name": "X-Gravitee-Request-Id",
                          "value": "{#request.id}"
                      }
                  ],
                  "variables": [
                      {
                          "name": "my-server",
                          "value": "{#jsonPath(#calloutResponse.content, '$.headers.X-Forwarded-Server')}"
                      }
                  ],
                  "exitOnError": true
              }
          }
        ]
      }
    ]
  }
}

```
*API CRD with basic callout*
```yaml
apiVersion: "gravitee.io/v1alpha1"
kind: "ApiV4Definition"
metadata:
    name: "policy-http-callout-proxy-api-crd"
spec:
    name: "HTTP Callout"
    type: "PROXY"
    flows:
      - name: "Common Flow"
        enabled: true
        selectors:
          - type: "HTTP"
            path: "/"
            pathOperator: "STARTS_WITH"
        request:
          - name: "HTTP Callout"
            enabled: true
            policy: "policy-http-callout"
            configuration:
              method: GET
              url: https://api.gravitee.io/echo
              headers:
                  - name: X-Gravitee-Request-Id
                    value: "{#request.id}"
              variables:
                  - name: my-server
                    value: "{#jsonPath(#calloutResponse.content, '$.headers.X-Forwarded-Server')}"
              exitOnError: true

```


## Changelog

### [5.3.0](https://github.com/gravitee-io/gravitee-policy-callout-http/compare/5.2.0...5.3.0) (2026-02-10)


##### Features

* add an option to evaluate as object ([73a0a81](https://github.com/gravitee-io/gravitee-policy-callout-http/commit/73a0a814de0b871bca4f6af1b7545b926f80b024))

### [5.2.0](https://github.com/gravitee-io/gravitee-policy-callout-http/compare/5.1.0...5.2.0) (2025-12-22)


##### Features

* kafka native ([6bab675](https://github.com/gravitee-io/gravitee-policy-callout-http/commit/6bab675395654fc983cba844589205cf94d8a4bc))

### [5.1.0](https://github.com/gravitee-io/gravitee-policy-callout-http/compare/5.0.0...5.1.0) (2025-12-11)


##### Features

* enable for LLM & MCP Proxy API ([7203e13](https://github.com/gravitee-io/gravitee-policy-callout-http/commit/7203e134879248a237df304b09ed1a4cb2608975))

### [5.1.0-alpha.1](https://github.com/gravitee-io/gravitee-policy-callout-http/compare/5.0.0...5.1.0-alpha.1) (2025-11-07)


##### Features

* enable for LLM & MCP Proxy API ([d1d2a25](https://github.com/gravitee-io/gravitee-policy-callout-http/commit/d1d2a25c6939c273961f461770398d8cc878c19b))

### [5.0.0](https://github.com/gravitee-io/gravitee-policy-callout-http/compare/4.0.3...5.0.0) (2025-07-16)


##### Features

* support traces span ([64c28d9](https://github.com/gravitee-io/gravitee-policy-callout-http/commit/64c28d94f129194dc2fa9ea605cac7e69c5562d9))


##### BREAKING CHANGES

* require at least APIM 4.6.0

#### [4.0.3](https://github.com/gravitee-io/gravitee-policy-callout-http/compare/4.0.2...4.0.3) (2025-06-18)


##### Bug Fixes

* generate new README using docgen ([a96179a](https://github.com/gravitee-io/gravitee-policy-callout-http/commit/a96179ab27307d23e5d3f9e413c3003968c4aa45))

#### [4.0.2](https://github.com/gravitee-io/gravitee-policy-callout-http/compare/4.0.1...4.0.2) (2025-05-16)


##### Bug Fixes

* handle fire and forget ([8207e28](https://github.com/gravitee-io/gravitee-policy-callout-http/commit/8207e2824ee0d0f8d970e40e7a67f94e4c2d64d2))

#### [4.0.1](https://github.com/gravitee-io/gravitee-policy-callout-http/compare/4.0.0...4.0.1) (2024-09-20)


##### Bug Fixes

* properly handle fire and forget in V4 ([40013b5](https://github.com/gravitee-io/gravitee-policy-callout-http/commit/40013b57b906d71fe7c9f8f960ac421987097396))

### [4.0.0](https://github.com/gravitee-io/gravitee-policy-callout-http/compare/3.0.0...4.0.0) (2024-08-01)


##### chore

* **deps:** bump dependencies ([c87a780](https://github.com/gravitee-io/gravitee-policy-callout-http/commit/c87a7801c5b5eb20bab30aa6f7d902eb6cef0696))


##### BREAKING CHANGES

* **deps:** require APIM 4.4.x

### [3.0.0](https://github.com/gravitee-io/gravitee-policy-callout-http/compare/2.0.2...3.0.0) (2023-11-24)


##### chore

* **deps:** update gravitee-parent ([5e52995](https://github.com/gravitee-io/gravitee-policy-callout-http/commit/5e5299591ab0e9795e262f3426c4cfca7c16e589))


##### Features

* migrate policy to support v4 API ([7d01bfe](https://github.com/gravitee-io/gravitee-policy-callout-http/commit/7d01bfefde48b5e153b53f1476bf166162440226))


##### BREAKING CHANGES

* **deps:** require Java17 and rxjava3

#### [2.0.2](https://github.com/gravitee-io/gravitee-policy-callout-http/compare/2.0.1...2.0.2) (2023-07-20)


##### Bug Fixes

* update policy description ([dcd71d6](https://github.com/gravitee-io/gravitee-policy-callout-http/commit/dcd71d6179e8cd3e603b5d3929115d699de14f82))

#### [2.0.1](https://github.com/gravitee-io/gravitee-policy-callout-http/compare/2.0.0...2.0.1) (2022-08-01)


##### Bug Fixes

* call callout endpoint with proper body when it contains accents ([52df3eb](https://github.com/gravitee-io/gravitee-policy-callout-http/commit/52df3eb10e9c5313a79f1dfc52e0b8f61a6e7fd3))

### [2.0.0](https://github.com/gravitee-io/gravitee-policy-callout-http/compare/1.15.0...2.0.0) (2022-05-24)


##### Code Refactoring

* use common vertx proxy options factory ([e643e56](https://github.com/gravitee-io/gravitee-policy-callout-http/commit/e643e56db9f72e6e517a3e1769250b0b851d092a))


##### BREAKING CHANGES

* this version requires APIM in version 3.18 and upper

### [1.15.0](https://github.com/gravitee-io/gravitee-policy-callout-http/compare/1.14.0...1.15.0) (2022-01-21)


##### Features

* **headers:** Internal rework and introduce HTTP Headers API ([c7fba2b](https://github.com/gravitee-io/gravitee-policy-callout-http/commit/c7fba2b165a182ffa978f8f85e29cc2a3261d83c)), closes [gravitee-io/issues#6772](https://github.com/gravitee-io/issues/issues/6772)

