# gavagai

Easy to use conversion library between tree-like POJOs or anything else presenting a bean-like interface and Clojure data structures. It can be used as a reasonably fast, configurable and recursive replacement for clojure.core/bean. It is intended as a tool to easily build a bridge between Clojure and Java when writing wrapper libraries.

## Motivation

Many Java APIs of perfectly good and usable libraries are based on the premises of returning data under the shape of trees of ad-hoc objects. Here in Clojureland, we have learned the benefits of using data under the form of uniform well-known structures, such as vectors and maps. They are optionally lazy, easier to reason about, fun to explore at the REPL, and so on and so forth... But in every ad-hoc mutable object from Javaland, there is a small core of data waiting to be freed. This is what gavagai endeavours to do, provide a simple, not too crazy and magical, declarative way to translate from Javaland to pure, immutable Clojureland data. It is also intended to be fast enough, and configurable enough to go wherever it needs to go to extract our precious data. It achieves these goals by using once-only reflection on the Java classes to create translation functions.

And if you wonder about the [name gavagai](http://en.wikipedia.org/wiki/Indeterminacy_of_translation), it is here to remind us that, though something is always lost in translation, we should still do our best to communicate with the people of our neighbouring lands...

## Installation

`gavagai` is available as a Maven artifact from
[Clojars](http://clojars.org/gavagai):

[![Clojars Project](http://clojars.org/gavagai/latest-version.svg)](http://clojars.org/gavagai)

## Usage

You need first to register in a `Translator` a converter for every Java class you need to have translated. By default, a `Translator` is created for you, initiliazed and returned with the converters registered:
```clojure
(ns my.nspace
  (:require [gavagai.core :as g]))

(def translator
  (g/register-converters
    {:exclude [:class]}
    [["java.util.Date" :add {:string str}]
     ["java.awt.Color" :only [:green #"r.d" :blue] :add {:string str} :lazy? false]
     ["java.awt.Button" :translate-arrays? true]
     ["java.awt.Button$AccessibleAWTButton" :exclude [:locale]]]))
```

The `register-converters` accepts a map as an optional first argument, which is the default options for every class registered. Individual options will be merged with the default options.

You can register a class by giving its name as a string, and add optional arguments to the declaration:
  - `:exclude` will exclude the given methods from the resulting map
  - `:only` will only include the given methods in the resulting map
  - `:lazy?` determines whether this object should be translated to a lazy-hash-map or a good old eager hash-map (defaults to lazy)
  - `:add` takes a map of key to functions and includes in the map the result of calling each function with the current object. For exemple, if you want to include the original object in the map, you can do:
  - `:translate-seqs?` will translate seq-like things (iterables and arrays) to seqs (or vectors if not lazy) (false by default)
  - `:translate-seq` is a vector of methods whose seq-like return (iterables and arrays) should be translated to seqs (or vectors if not lazy).
  - `:super?` will determine if the created translator should check ancestors and interfaces for converters (false by default and not used if a `Translator` is explicitely given)
  - `:throw?` determines  whether trying to register a converter for a class that does not exist should throw an exception or be silently ignored. (true by default)
  - `:force` is a vector of strings naming methods for which the creation of getters will be forced, even if Java reflection cannot pick them.
  - `:custom-converter` directly registers a custom converter fn for this class. It takes 3 args, [translator object options]. If you do not need to call convert again, you can ignore the first and third.
  - `:sweeten-name?` makes method names more idiomatic for Clojure, defaults to true
  - `:separator` specifies which separator to use when converting from CamelCase, defaults to "-"

You can then call `translate` with the correct `Translator` on any registered object belonging to a registered class and itself and its members will be recursively translated. The translate function takes a map as an optional third argument, these params override the ones given in the converter.
  - `:lazy?`            -> boolean (overrides the param given in the spec)
  - `:omit-cyclic-ref?` -> boolean (do not translate objects already seen in the object graph, to avoid stack overflows)
  - `:max-depth`        -> integer (for recursive or very deep graph objects)

```clojure
(let [color (java.awt.Color. 10 10 10)]
      (g/translate translator color))
=> {:red 10, :blue 10, :green 10, :string "java.awt.Color[r=10,g=10,b=10]"}

;; You can also create a translating fn with the translator baked-in
;; (remenber Translators are immutable structures)
(def my-translate (partial g/translate translator))
(my-translate (java.awt.Color. 10 10 10))
=> {:red 10, :blue 10, :green 10, :string "java.awt.Color[r=10,g=10,b=10]"}

;; There is also a macro to avoid specifying the Translator:
(let [b (java.awt.Button. "test")]
      (g/with-translator translator
        (g/translate b {:max-depth 2})])
=> {:accessible-context
    {:accessible-role #<AccessibleRole push button>,
     :accessible-action
       ;; though registered, this does not get translated because it is 3 levels deep
     #<AccessibleAWTButton java.awt.Button$AccessibleAWTButton@1bfdfa36>,
     :background nil,
     :foreground nil,
       ;; snip...
     :focusable? true,
     :label "test",
     :font nil}}
```

 The map keys are keywords obtained by removing the `get` or `is` prefix, hyphenizing the java method name, and adding a final `?` if the method returns a boolean. You can check what keys gavagai will use for every eligible methods by using the `inspect-class` function. You can also use regexp patterns instead of keywords to select methods in the options.

```clojure
(g/inspect-class java.util.Date)
=> {"getTimezoneOffset" :timezone-offset, "getClass" :class, "getTime"
:time, "getDate" :date, "getDay" :day, "getMinutes" :minutes,
"getSeconds" :seconds, "getMonth" :month, "getYear" :year, "getHours"
:hours}

(g/inspect-class String)
=> {"isEmpty" :empty?, "getBytes" :bytes, "getClass" :class}
```

 There are also functions to inspect the fields returned by a converter, with all declared options taken into account:

```clojure
(g/get-class-fields translator java.awt.Color)
=> #{:red :string :blue :green}
```

 If you need to register a custom converter for a class, you can do it with `add-converter`. The converter is a plain Clojure function that takes 3 parameters, [translator object runtime-opts].

```clojure
(let [color (java.awt.Color. 10 10 10)
      f (fn [_ ^java.aws.Color color _]
          (str "#" (apply str (drop 2 (Integer/toHexString (.getRGB color))))))
      new-translator (g/add-converter translator java.aws.Color f)
        ;; there is also a simpler syntax with register-converters
      same-translator (g/register-converters translator
                        [["java.aws.Color" :custom-converter f]])]
  (g/translate new-translator color)
=> "#0a0a0a"
```

 To see a full-fledged exemple of gavagai use to build a wrapper around a very Java-centric API, you can check the code of [clj-rome](https://github.com/ngrunwald/clj-rome).

## Related Info and Blog Posts

  - [Introducing Gavagai](http://theblankscreen.net/blog/2013/02/18/introducing-gavagai/) wich reveals some of the motivation behind the project  

## Performance and Caveats

 The resulting maps are by default fully lazy (as `core/bean`). If you need to serialize or pass around the value, you should call translate with the `lazy?` set to false, to get a fully realized structure. Be careful about infinite loop in objects graph if you do this. You can set `:omit-cyclic-ref?` to `true` or specify a `:max-depth` when calling translate to guard against this.

## License

Copyright © 2012, 2013 Nils Grunwald

Distributed under the Eclipse Public License, the same as Clojure.
