# gavagai

Easy to use conversion library between tree-like POJOs or anything else presenting a bean-like interface and Clojure data structures. It can be used as a fast, configurable and recursive replacement for core/bean. It is intended as a tool to easily build a bridge between Clojure and Java when writing wrapper libraries.

## Usage

You need first to register a converter for every Java class you need to have translated, like so:
```clojure
(ns my.nspace
  (:require [gavagai.core :as g]))

(g/register-converters
 ["java.util.Date" :exclude [:class] :add {:string str}]
 ["java.awt.Color" :only [:green :red :blue] :add {:string str} :lazy? false]
 ["java.awt.Button" :exclude [:class] :translate-arrays? true :lazy? false]
 ["java.awt.Button$AccessibleAWTButton" :exclude [:class :locale] :lazy? false])
```

The converters are registered under the namespace in which they are declared. They will not conflict with converters on the same classes registered in other namespaces.

You can register a class by giving its name as a string, and add optional arguments to the declaration:
  - `:exclude` will exclude the given methods from the resulting map
  - `:only` will only include the given methods in the resulting map
  - `:lazy?` determines whether this object should be translated to a lazy-hash-map or a good old eager hash-map
  - `:add` takes a map of key to functions and includes in the map the result of calling each function with the current object. For exemple, if you want to include the original object in the map, you can do:
  - `:translate-arrays?` will translate any java-array returned by the methods of this object to Clojure vectors

You can then call `translate` with the correct namespace on any registered POJO belonging to a registered class and itself and its children will be recursively translated.

```clojure
(let [b (java.awt.Color. 10 10 10)]
      (g/translate b {:nspace 'my.nspace}))
=> {:red 10, :blue 10, :green 10, :string "java.awt.Color[r=10,g=10,b=10]"}

;; There is also a macro to avoid specifying the namespace in the options:
(let [b (java.awt.Button. "test")]
      (g/with-translator-ns my.nspace
        (g/translate b {:max-depth 2})])
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

 The map keys are keywords obtained by removing the `get` or `is` prefix, hyphenizing the java method name, and adding a final `?` if the method returns a boolean. You can check what keys gavagai will use for every eligible methods by using the `inspect-class` function.

```clojure
(g/inspect-class java.util.Date)
=> {"getTimezoneOffset" :timezone-offset, "getClass" :class, "getTime"
:time, "getDate" :date, "getDay" :day, "getMinutes" :minutes,
"getSeconds" :seconds, "getMonth" :month, "getYear" :year, "getHours"
:hours}

(inspect-class String)
=> {"isEmpty" :empty?, "getBytes" :bytes, "getClass" :class}
```

## Performance and Caveats

gavagai registers at compilation time static a protocol on each registered class with a type-hinted static function to realize the translation. As such, it is quite fast (some quick tests seem to imply it is about 20% faster than `core/bean`, which uses runtime reflection to translate the objects).
Also the resulting maps are by default fully lazy (as `core/bean`). If you need to serialize or pass around the value, you should fully realize it first (with `doall` for example). Be careful about infinite loop in objects graph if you do this. You can specify a `:max-depth` when calling translate to guard against this, or register a converter with the :lazy? option set to false.

## License

Copyright © 2012 Nils Grunwald

Distributed under the Eclipse Public License, the same as Clojure.
