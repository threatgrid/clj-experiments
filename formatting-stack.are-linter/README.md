# formatting-stack.are-linter

A [formatting-stack](https://github.com/nedap/formatting-stack) linter for `clojure.test/are` forms.    

It currently lints just one thing: that any given templated form that is an _invocation_, appears just once in `are`'s `expr` argument.

That way we can be sure that side-effects aren't repeated, which can lead to confusing reports or brittle tests.

## Rationale

This is an example of an `are` form that templates an invocation more than once:

```clj
(deftest foo-test
  (are [x] (testing x
             (is (forbidden? x))
             (is (not (authorized? x))))
    nil
    "id-123"
    (str (java.util.UUID/randomUUID))))
```

...in the snippet above, the `(str (java.util.UUID/randomUUID))` form will be invoked 3 times. That's not what one would expect:

* the `testing` form will report one generated value, while the `is` forms will exercise _different_ generated values
  * i.e. reports would be spurious.
* the `forbidden?` and `authorized?` assertions exercise _different_ values, which creates a logically inconsistent test
  * this can easily lead to false positives or negatives.  

These gotchas justify the existence of `formatting-stack.are-linter`, so that one can use vanilla `clojure.test/are` (as opposed to a custom replacement) with full confidence.

## Installation

```clojure
[threatgrid/formatting-stack.are-linter "0.1.0-alpha1"]
```

## Usage

This is a standard [formatting-stack](https://github.com/nedap/formatting-stack) linter so it follows its typical patterns.

You should be able to add `(formatting-stack.are-linter.api/new)` to your 'stack' of linters.

This linter is Clojure/Script compatible. The ns-form parsing (necessary for processing e.g. `(foo/are` aliased forms) is particularly precise in JVM clojure. cljs falls back to tools.reader heuristics.

## Development

The default namespace is `dev`. Under it, `(refresh)` is available, which should give you a basic "Reloaded workflow".

> It is recommended that you use `(clojure.tools.namespace.repl/refresh :after 'formatting-stack.core/format!)`.

## License

Copyright © Cisco

This program and the accompanying materials are made available under the terms of the [Eclipse Public License 2.0](https://www.eclipse.org/legal/epl-2.0).
