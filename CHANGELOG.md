# Changelog

## [0.4.3](https://github.com/sunix/diderot/compare/v0.4.2...v0.4.3) (2026-10-26)


### Bug Fixes

* **release:** build the Linux binaries against glibc 2.28 ([#54](https://github.com/sunix/diderot/issues/54)) ([941ab49](https://github.com/sunix/diderot/commit/941ab49847054b751fd545f378d2cbff3517ae99))

## [0.4.2](https://github.com/sunix/diderot/compare/v0.4.1...v0.4.2) (2026-10-25)


### Bug Fixes

* **native:** register the sigstore clients, and sign for real on a branch ([#50](https://github.com/sunix/diderot/issues/50)) ([6fd5fc0](https://github.com/sunix/diderot/commit/6fd5fc0c1840af6c483f240acad17c833f43b6b1))

## [0.4.1](https://github.com/sunix/diderot/compare/v0.4.0...v0.4.1) (2026-10-24)


### Bug Fixes

* **native:** ship sigstore's trust roots inside the binary ([#47](https://github.com/sunix/diderot/issues/47)) ([1d348de](https://github.com/sunix/diderot/commit/1d348dec10a49baac03967b7330fb8f51d3b432f))

## [0.4.0](https://github.com/sunix/diderot/compare/v0.3.0...v0.4.0) (2026-10-24)


### Features

* sign the content, carry the signature with it, and verify who signed ([#42](https://github.com/sunix/diderot/issues/42)) ([2373076](https://github.com/sunix/diderot/commit/2373076416cc4c6a8555d4c3cf75717ca3d60831))
* signing, step one — the sigstore boundary and the native-image question ([#38](https://github.com/sunix/diderot/issues/38)) ([da445b8](https://github.com/sunix/diderot/commit/da445b8d8563362d8792be33014bd476a98cccd3))

## [0.3.0](https://github.com/sunix/diderot/compare/v0.2.0...v0.3.0) (2026-08-27)


### Features

* add `diderot add` and `diderot remove` ([#34](https://github.com/sunix/diderot/issues/34)) ([2729967](https://github.com/sunix/diderot/commit/2729967b028d360dfb911d9ca7f0719477c00fb7))


### Documentation

* correct chapter five's open section, and what it was run against ([#28](https://github.com/sunix/diderot/issues/28)) ([16379c5](https://github.com/sunix/diderot/commit/16379c5bed22dfd486bc6a3ee7a20aa6295950e6))

## [0.2.0](https://github.com/sunix/diderot/compare/v0.1.0...v0.2.0) (2026-08-25)


### Features

* **oci:** resolve semver ranges over registry tags ([#26](https://github.com/sunix/diderot/issues/26)) ([73ecc53](https://github.com/sunix/diderot/commit/73ecc53b6c7e66c0e7c8fbde2e63e9743f8e40e1))


### Bug Fixes

* **ci:** replace the retired macos-13 runner with macos-15-intel ([#15](https://github.com/sunix/diderot/issues/15)) ([d84c36e](https://github.com/sunix/diderot/commit/d84c36e705f0a1ea85fafc3e0b5ee59523d62ff4))


### Documentation

* add chapter four, releasing the skills for real ([#22](https://github.com/sunix/diderot/issues/22)) ([947517e](https://github.com/sunix/diderot/commit/947517e6af25cade69d2ea06d60681f831a73b06))
* reader-first prose across the making-of chapters ([#18](https://github.com/sunix/diderot/issues/18)) ([bfe1dd8](https://github.com/sunix/diderot/commit/bfe1dd88ade6d2dd66ad233de4a8198514615722))
* v0.1.0 shipped — the release, its two remaining traps, and real proof ([#16](https://github.com/sunix/diderot/issues/16)) ([58c49f8](https://github.com/sunix/diderot/commit/58c49f85fe18a9e2f2de61df579f390666164442))

## 0.1.0 (2026-08-24)


### Features

* git-source resolver, content-digest lockfile, install and status (M1) ([#2](https://github.com/sunix/diderot/issues/2)) ([bc6c2fc](https://github.com/sunix/diderot/commit/bc6c2fc7354f9538c29c3ef8bbe470b09a2aa099))
* native binaries, one-line installer and JBang catalog (M3) ([#9](https://github.com/sunix/diderot/issues/9)) ([e012c66](https://github.com/sunix/diderot/commit/e012c66f72ed458694c8479936d03acc5e33fd36))
* OCI registry backend — push skills as OCI artifacts, digest-pinned oci:// sources (M2) ([#5](https://github.com/sunix/diderot/issues/5)) ([f9e6998](https://github.com/sunix/diderot/commit/f9e69982cd98416368506bb6dcdd9220e5ca3937))
* Quarkus picocli walking skeleton with Helm-style command surface ([69db08a](https://github.com/sunix/diderot/commit/69db08a48756561c25327607fd630cb920a63a09))


### Documentation

* close chapter 2 with a real CI push and a cross-repo install ([#8](https://github.com/sunix/diderot/issues/8)) ([97ba147](https://github.com/sunix/diderot/commit/97ba1474f5d207f06548c48fe18eb7de61df431f))
* document how to cut a release, including the traps ([#12](https://github.com/sunix/diderot/issues/12)) ([ea7ec40](https://github.com/sunix/diderot/commit/ea7ec4047a9bd9b2e07395102ede7bf3c2597647))
* **making-of:** ground the Java choice in the author's actual background ([#3](https://github.com/sunix/diderot/issues/3)) ([4a92ec2](https://github.com/sunix/diderot/commit/4a92ec23713e783b260812138db2a834e32b9b53))
* record the release saga in the packaging chapter ([#13](https://github.com/sunix/diderot/issues/13)) ([710845a](https://github.com/sunix/diderot/commit/710845a8d945f10cc5d93dda8576d8b06dd04982))
