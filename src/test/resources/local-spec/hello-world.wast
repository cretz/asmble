;; TODO: test w/ emscripten compiled code

(module
  (import "spectest" "print" (func $print (param i32)))
  (func (export "printNum") (param $i i32)
    (call $print (get_local $i))
  )
)