# gavagai

Easy to use conversion library between tree-like POJOs or anything else presenting a bean-like interface and Clojure data structures. It can be used as a reasonably fast, configurable and recursive replacement for clojure.core/bean. It is intended as a tool to easily build a bridge between Clojure and Java when writing wrapper libraries.

## Installation

`gavagai` is available as a Maven artifact from
[Clojars](http://clojars.org/gavagai):

```clojure
[gavagai "0.4.0"]
```

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
  - `:lazy?` determines whether this object should be translated to a lazy-hash-map or a good old eager hash-map
  - `:add` takes a map of key to functions and includes in the map the result of calling each function with the current object. For exemple, if you want to include the original object in the map, you can do:
  - `:translate-seqs?` will translate seq-like things (iterables and arrays) to seqs (or vectors if not lazy) (false by default)
  - `:translate-seq` is a vector of methods whose seq-like return (iterables and arrays) should be translated to seqs (or vectors if not lazy).
  - `:super?` will determine if the created translator should check ancestors and interfaces for converters (false by default and not used if a `Translator` is explicitely given)
  - `:throw?` determines  whether trying to register a converter for a class that does not exist should throw an exception or be silently ignored. (true by default)

You can then call `translate` with the correct `Translator` on any registered object belonging to a registered class and itself and its members will be recursively translated. The translate function takes a map as second argument, these params override the ones given in the converter.
  - `:max-depth` -> integer (for recursive graph objects, to avoid infinite loops)
  - `:lazy?`     -> boolean (overrides the param given in the spec)

```clojure
(let [b (java.awt.Color. 10 10 10)]
      (g/translate translator b))
=> {:red 10, :blue 10, :green 10, :string "java.awt.Color[r=10,g=10,b=10]"}

;; You can also create a translating fn with the translator baked-in
;; (remenber Translators are immutable structures)
(def my-translate (partial g/translate translator {:lazy? false}))
(my-translate (java.awt.Color. 10 10 10))
=> {:red 10, :blue 10, :green 10, :string "java.awt.Color[r=10,g=10,b=10]"}

;; There is also a macro to avoid specifying the Translator:
(let [b (java.awt.Button. "test")]
      (g/with-translator translator
        (g/translate {:max-depth 2} b)])
=> {:accessible-context {:accessible-role #<AccessibleRole push button>,
:accessible-action #<AccessibleAWTButton
java.awt.Button$AccessibleAWTButton@63f8c10e>, :background nil,
:foreground nil, :accessible-name "test", :minimum-accessible-value 0,
:class java.awt.Button$AccessibleAWTButton, :visible? true,
:accessible-selection nil, :location #<Point java.awt.Point[x=0,y=0]>,
:bounds #<Rectangle java.awt.Rectangle[x=0,y=0,width=0,height=0]>,
:accessible-icon nil, :accessible-text nil, :accessible-description
nil, :cursor #<Cursor java.awt.Cursor[Default Cursor]>, :size
#<Dimension java.awt.Dimension[width=0,height=0]>,
:accessible-index-in-parent -1, :current-accessible-value 0,
:location-on-screen nil, :accessible-action-count 1,
:focus-traversable? true, :enabled? true, :accessible-children-count
0, :accessible-editable-text nil, :accessible-parent nil,
:accessible-table nil, :accessible-relation-set
#<AccessibleRelationSet >, :accessible-state-set #<AccessibleStateSet
enabled,focusable,visible>, :font nil, :accessible-component
#<AccessibleAWTButton java.awt.Button$AccessibleAWTButton@63f8c10e>,
:showing? false, :maximum-accessible-value 0, :accessible-value
#<AccessibleAWTButton java.awt.Button$AccessibleAWTButton@63f8c10e>},
:background nil, :foreground nil, :name "button1", :visible? true,
:action-command "test", :action-listeners [], :enabled? true,
:focusable? true, :label "test", :font nil}
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
=> #{:red :green :blue}
```

 If you need to register a custom converter for a class, you can do it with `add-converter`. The converter is a plain Clojure function that takes 3 parameters, [translator object runtime-opts].

```clojure
(add-converter translator java.aws.Color
  (fn [_ ^java.aws.Color color _]
    {:red (.red color) :green (.gree color) :blue (.blue color)}))
```

 To see a full-fledged exemple of gavagai use to build a wrapper around a very Java-centric API, you can check the code of [clj-rome](https://github.com/ngrunwald/clj-rome).

## Performance and Caveats

 The resulting maps are by default fully lazy (as `core/bean`). If you need to serialize or pass around the value, you should call translate with the `lazy?` set to false, to get a fully realized structure. Be careful about infinite loop in objects graph if you do this. You can specify a `:max-depth` when calling translate to guard against this.

## License

Copyright © 2012, 2013 Nils Grunwald

Distributed under the Eclipse Public License, the same as Clojure.
