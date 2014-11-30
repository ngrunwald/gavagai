# gavagai changelog

## v0.3.2
  - added `:sweeten-name?` and `:separator` to converter options
  - added special handling of Enums (translate them directly to keyword of their value)

## v0.3.1

  - added the `:omit-cycle?` option to guard against cycle in objects graphs (Nicolas Yzet)
  - added possibility to register `:custom-converter` with `register-converters` macro
  - added `:force` option to to force translation of specified object fields
