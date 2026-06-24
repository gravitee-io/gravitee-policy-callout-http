# [7.0.0](https://github.com/gravitee-io/gravitee-policy-callout-http/compare/6.0.2...7.0.0) (2026-06-24)


### Bug Fixes

* bump apim dependency to 4.12.0-milestone.1 ([262893f](https://github.com/gravitee-io/gravitee-policy-callout-http/commit/262893f98712fea57cbc0e51856118cb3a0a3abf))
* configure callout connection pool via Vert.x 5 PoolOptions ([4ee3f98](https://github.com/gravitee-io/gravitee-policy-callout-http/commit/4ee3f98b57aa30763b800fc9c7ded0f5c0de8a9b)), closes [PoolOptions#setHttp1](https://github.com/PoolOptions/issues/setHttp1)
* performance improvement : make http connection pool configurable for http callout policy ([805378f](https://github.com/gravitee-io/gravitee-policy-callout-http/commit/805378f2eafe5e2db202a6be0546fd8342920aea))


### Features

* upgrade to vertx 5 ([60edb56](https://github.com/gravitee-io/gravitee-policy-callout-http/commit/60edb568ab24748eaa56ca52c90c90c04771784d))


### BREAKING CHANGES

* upgrade to vertx 5.x

## [6.0.2](https://github.com/gravitee-io/gravitee-policy-callout-http/compare/6.0.1...6.0.2) (2026-06-08)


### Bug Fixes

* support multi-line editing for request body field ([#191](https://github.com/gravitee-io/gravitee-policy-callout-http/issues/191)) ([b921f98](https://github.com/gravitee-io/gravitee-policy-callout-http/commit/b921f9806ca81cc2b263684ab04b73554a342ce4))

## [6.0.1](https://github.com/gravitee-io/gravitee-policy-callout-http/compare/6.0.0...6.0.1) (2026-04-30)


### Bug Fixes

* performance improvement : make http connection pool configurable for http callout policy ([0222ff5](https://github.com/gravitee-io/gravitee-policy-callout-http/commit/0222ff587efcbb16935da060f2e2db8b20928e29))

# [6.0.0](https://github.com/gravitee-io/gravitee-policy-callout-http/compare/5.4.0...6.0.0) (2026-03-25)


### Bug Fixes

* rework http client creation ([aafb92a](https://github.com/gravitee-io/gravitee-policy-callout-http/commit/aafb92ad721b3b74d90e27c2559b1fb21d573cc5))
* update KafkaMessageStub implementation ([08d892e](https://github.com/gravitee-io/gravitee-policy-callout-http/commit/08d892e994efdd5f11f7c0707107334e0d026712))


### chore

* bump dependencies ([70c98a6](https://github.com/gravitee-io/gravitee-policy-callout-http/commit/70c98a68c604234ba43f334a1a24fd5607f56c5c))


### Features

* improve v3 performance by using one http client only ([b4a42cf](https://github.com/gravitee-io/gravitee-policy-callout-http/commit/b4a42cffa3c7db0cf7e550abab0461cee8b0fe97))
* propagate failure cause ([19ef61e](https://github.com/gravitee-io/gravitee-policy-callout-http/commit/19ef61eba52f3126f5c271cd3481ee86e0e27a97))
* use new logging ([7258910](https://github.com/gravitee-io/gravitee-policy-callout-http/commit/72589105f1b579c8386c06628f7f1b81681657c6))


### BREAKING CHANGES

* bump gravitee-parent

# [5.4.0](https://github.com/gravitee-io/gravitee-policy-callout-http/compare/5.3.0...5.4.0) (2026-02-18)


### Features

* enable A2A proxy ([05b1af8](https://github.com/gravitee-io/gravitee-policy-callout-http/commit/05b1af8ec37c37800bd7ecee68aee810b44f417d))

# [5.3.0](https://github.com/gravitee-io/gravitee-policy-callout-http/compare/5.2.0...5.3.0) (2026-02-10)


### Features

* add an option to evaluate as object ([73a0a81](https://github.com/gravitee-io/gravitee-policy-callout-http/commit/73a0a814de0b871bca4f6af1b7545b926f80b024))

# [5.2.0](https://github.com/gravitee-io/gravitee-policy-callout-http/compare/5.1.0...5.2.0) (2025-12-22)


### Features

* kafka native ([6bab675](https://github.com/gravitee-io/gravitee-policy-callout-http/commit/6bab675395654fc983cba844589205cf94d8a4bc))

# [5.1.0](https://github.com/gravitee-io/gravitee-policy-callout-http/compare/5.0.0...5.1.0) (2025-12-11)


### Features

* enable for LLM & MCP Proxy API ([7203e13](https://github.com/gravitee-io/gravitee-policy-callout-http/commit/7203e134879248a237df304b09ed1a4cb2608975))

# [5.1.0-alpha.1](https://github.com/gravitee-io/gravitee-policy-callout-http/compare/5.0.0...5.1.0-alpha.1) (2025-11-07)


### Features

* enable for LLM & MCP Proxy API ([d1d2a25](https://github.com/gravitee-io/gravitee-policy-callout-http/commit/d1d2a25c6939c273961f461770398d8cc878c19b))

# [5.0.0](https://github.com/gravitee-io/gravitee-policy-callout-http/compare/4.0.3...5.0.0) (2025-07-16)


### Features

* support traces span ([64c28d9](https://github.com/gravitee-io/gravitee-policy-callout-http/commit/64c28d94f129194dc2fa9ea605cac7e69c5562d9))


### BREAKING CHANGES

* require at least APIM 4.6.0

## [4.0.3](https://github.com/gravitee-io/gravitee-policy-callout-http/compare/4.0.2...4.0.3) (2025-06-18)


### Bug Fixes

* generate new README using docgen ([a96179a](https://github.com/gravitee-io/gravitee-policy-callout-http/commit/a96179ab27307d23e5d3f9e413c3003968c4aa45))

## [4.0.2](https://github.com/gravitee-io/gravitee-policy-callout-http/compare/4.0.1...4.0.2) (2025-05-16)


### Bug Fixes

* handle fire and forget ([8207e28](https://github.com/gravitee-io/gravitee-policy-callout-http/commit/8207e2824ee0d0f8d970e40e7a67f94e4c2d64d2))

## [4.0.1](https://github.com/gravitee-io/gravitee-policy-callout-http/compare/4.0.0...4.0.1) (2024-09-20)


### Bug Fixes

* properly handle fire and forget in V4 ([40013b5](https://github.com/gravitee-io/gravitee-policy-callout-http/commit/40013b57b906d71fe7c9f8f960ac421987097396))

# [4.0.0](https://github.com/gravitee-io/gravitee-policy-callout-http/compare/3.0.0...4.0.0) (2024-08-01)


### chore

* **deps:** bump dependencies ([c87a780](https://github.com/gravitee-io/gravitee-policy-callout-http/commit/c87a7801c5b5eb20bab30aa6f7d902eb6cef0696))


### BREAKING CHANGES

* **deps:** require APIM 4.4.x

# [3.0.0](https://github.com/gravitee-io/gravitee-policy-callout-http/compare/2.0.2...3.0.0) (2023-11-24)


### chore

* **deps:** update gravitee-parent ([5e52995](https://github.com/gravitee-io/gravitee-policy-callout-http/commit/5e5299591ab0e9795e262f3426c4cfca7c16e589))


### Features

* migrate policy to support v4 API ([7d01bfe](https://github.com/gravitee-io/gravitee-policy-callout-http/commit/7d01bfefde48b5e153b53f1476bf166162440226))


### BREAKING CHANGES

* **deps:** require Java17 and rxjava3

## [2.0.2](https://github.com/gravitee-io/gravitee-policy-callout-http/compare/2.0.1...2.0.2) (2023-07-20)


### Bug Fixes

* update policy description ([dcd71d6](https://github.com/gravitee-io/gravitee-policy-callout-http/commit/dcd71d6179e8cd3e603b5d3929115d699de14f82))

## [2.0.1](https://github.com/gravitee-io/gravitee-policy-callout-http/compare/2.0.0...2.0.1) (2022-08-01)


### Bug Fixes

* call callout endpoint with proper body when it contains accents ([52df3eb](https://github.com/gravitee-io/gravitee-policy-callout-http/commit/52df3eb10e9c5313a79f1dfc52e0b8f61a6e7fd3))

# [2.0.0](https://github.com/gravitee-io/gravitee-policy-callout-http/compare/1.15.0...2.0.0) (2022-05-24)


### Code Refactoring

* use common vertx proxy options factory ([e643e56](https://github.com/gravitee-io/gravitee-policy-callout-http/commit/e643e56db9f72e6e517a3e1769250b0b851d092a))


### BREAKING CHANGES

* this version requires APIM in version 3.18 and upper

# [1.15.0](https://github.com/gravitee-io/gravitee-policy-callout-http/compare/1.14.0...1.15.0) (2022-01-21)


### Features

* **headers:** Internal rework and introduce HTTP Headers API ([c7fba2b](https://github.com/gravitee-io/gravitee-policy-callout-http/commit/c7fba2b165a182ffa978f8f85e29cc2a3261d83c)), closes [gravitee-io/issues#6772](https://github.com/gravitee-io/issues/issues/6772)
