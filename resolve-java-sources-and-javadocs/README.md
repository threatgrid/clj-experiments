# resolve-java-sources-and-javadocs

A Leiningen plugin that automatically downloads all available `.jar`s with Java sources and javadocs for a given project, so that various tooling (typically IDEs) can access it.

It behaves gracefully even in face of `:managed-dependencies`, `:pedantic?`,  in- and inter-process parallel invocations of Lein, etc.

For efficiency, it has caching that is shared across projects, and dependency resolution is parallel.

## A sample use case

As a quick example of you can do with it: 

```clj
(defn class->source [class-object]
  {:pre [(class? class-object)]}
  (-> class-object pr-str munge (string/replace "." "/") (str ".java") (io/resource) slurp))

;; Usage: (-> Thread class->source println)
```

All what this plugin does is placing a source (and/or javadoc) `.jar` in the classpath, so that `(io/resource)` will return it (else it would return `nil`).

A great real-world lib that would be enhanced by this plugin is Orchard's [source-info](https://github.com/clojure-emacs/orchard/blob/f8a85feb613501be0896c3683c8ff7b0bd404061/src/orchard/java/parser.clj#L290).

## Installation and usage

Add the following somewhere in your `~/.lein/profiles.clj` (aka your [user-wide profile](https://github.com/technomancy/leiningen/blob/0f456829a8b21335aa86390f3ee3d0dcc68410d6/doc/PROFILES.md#declaring-profiles)):

```clj
;; Installing this plugin under the :repl profile is most recommended for best performance,
;; especially if you work on a monorepo with a complex build process.  
:repl {:middleware [leiningen.resolve-java-sources-and-javadocs/add]
       :plugins [[threatgrid/resolve-java-sources-and-javadocs "0.1.12"]]
       ;; Optional - you can use this option to specify a different set (e.g. a smaller set like #{"sources"} is more performant)
       :resolve-java-sources-and-javadocs {:classifiers #{"sources" "javadoc"}}}

```

> If adding this middleware on a per-project basis, make sure it's not turned on by default, simply because other people might not appreciate a slower (first) dependency resolution for a functionality that they might not use. [Profiles](https://github.com/technomancy/leiningen/blob/master/doc/PROFILES.md) help.  

After that, `lein repl` and similar commands will download each artifact of your dependency tree with `"sources"` and `"javadoc"` Maven qualifiers, if such an artifact exists (normally these only exist for Java dependencies, not Clojure ones), and place it in the classpath for your REPL process. 

## Notes on caching

Running this middleware _for the first time_ on a given project will be slow (think: anything between 1-5m). The more dependencies your project has, especially Java ones, the slower this run will be.

Each time a source or javadoc `.jar` is found, the found artifact will be logged, so that you can see that the plugin is in fact doing something:

```
:leiningen.resolve-java-sources-and-javadocs/found [org.clojure/clojure "1.10.1" :classifier "sources"]
```

After a successful run, a cache file is written to `~/.lein-source-and-javadocs-cache`. This file is shared across all projects, and will automatically grow via merge. So the first few runs in a variety of projects will result in a slow dependency resolution, and after that it will stabilize in those projects (and best-case scenario, also in _other_ projects)

Given a project with 100% cache hits (which eventually will be the case in all your projects, after a while), this plugin's runtime overhead will be essentially zero.

The `~/.lein-source-and-javadocs-cache` file has a stable format. You can version-control it, so that if you setup a new machine you won't have cache misses.

## Options

By default, both sources and javadocs will be fetched. By specifying only `sources` to be fetched, one gets a 2x performance improvement (because 0.5x as many items will be attempted to be resolved):

```clj
:resolve-java-sources-and-javadocs {:classifiers #{"sources"}}
```

You can also specify classifiers other than `"sources", "javadoc"`, if that is useful to you.

## [Note if using refactor-nrepl, CIDER, Orchard](https://github.com/clojure-emacs/refactor-nrepl/issues/290) 

## License

Copyright © Cisco Systems

This program and the accompanying materials are made available under the terms of the [Eclipse Public License 2.0](https://www.eclipse.org/legal/epl-2.0).
