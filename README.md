# gavagai

Easy to use conversion library between tree-like POJOs and lazy Clojure data structures. It can be used as a fast, configurable and recursive replacement for core/bean.

## Usage

You need first to register a converter for every Java class you need to have translated, like so:
```clojure
(register-converters
  ["java.util.concurrent.ThreadPoolExecutor" :exclude [:class]])
```

You can register a class by giving its name as a string, and add optional arguments to the declaration:
  - `:exclude` will exclude the given methods from the resulting map
  - `:only` will only include the given methods in the resulting map
  - `:add` takes a map of key to functions and includes in the map the result of calling each function with the current object. For exemple, if you want to include the original object in the map, you can do:

```clojure
(register-converters
  ["java.util.concurrent.ThreadPoolExecutor" :add {:original identity}])
```

You can then call `translate` on any registered POJO belonging to a registered class and itself and its children will be recursively translated.

## Performance and Caveats

gavagai registers at compilation time static a protocol on each registered class with a type-hinted static function to realize the translation. As such, it is quite fast (some quick tests seem to imply it is about 20% faster than `core/bean`, which uses runtime reflection to translate the objects).
Also the resulting maps are fully lazy (as `core/bean`). If you need to serialize or pass around the value, you should fully realize it first (with `doall` for example). Be careful about infinite loop in objects graph if you do this. You can specify a `:max-depth` when calling translate to guard against this:
```clojure
(translate obj {:max-depth 6})
;; -> {...}
```

## License

Copyright © 2012 Nils Grunwald

Distributed under the Eclipse Public License, the same as Clojure.
