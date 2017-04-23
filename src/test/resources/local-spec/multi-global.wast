;; This was breaking because stack diff was wrong for get_global and set_global

(module
  (global $foo (mut i32) (i32.const 20))
  (func (export "test") (param $p i32) (result i32)
    (local i32)
    (get_global $foo)
    (set_local 1)
    (get_global $foo)
    (get_local $p)
    (i32.add)
    (set_global $foo)
    (get_global $foo)
    (i32.const 15)
    (i32.add)
    (i32.const -16)
    (i32.and)
    (set_global $foo)
    (get_global $foo)
  )
)

(assert_return (invoke "test" (i32.const 7)) (i32.const 32))