(module
  (memory 1)

  (global $foo (mut i32) (i32.const 20))
  (global $bar (mut f32) (f32.const 0))

  ;; This was breaking because stack diff was wrong for get_global and set_global
  (func (export "testGlobals") (param $p i32) (result i32)
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

  ;; Sqrt had bad stack diff
  (func (export "testSqrt") (param $p f32) (result f32)
    (set_global $bar (f32.sqrt (get_local $p)))
    (get_global $bar)
  )

  ;; Conditionals w/ different load counts had bad stack diff
  (func (export "testConditional") (param $p i32) (result i32)
    (get_local $p)
    (if (result i32) (get_local $p)
      (then (i32.load (get_local $p)))
      (else
        (i32.add
          (i32.load (get_local $p))
          (i32.load (get_local $p))
        )
      )
    )
    (i32.store)
    (i32.load (get_local $p))
  )
)

(assert_return (invoke "testGlobals" (i32.const 7)) (i32.const 32))
(assert_return (invoke "testSqrt" (f32.const 144)) (f32.const 12))