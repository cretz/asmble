(module
 (type $FUNCSIG$ii (func (param i32) (result i32)))
 (type $FUNCSIG$iii (func (param i32 i32) (result i32)))
 (import "env" "printf" (func $printf (param i32 i32) (result i32)))
 (import "env" "memory" (memory $0 256))
 (table 0 anyfunc)
 (data (i32.const 1040) "%s, %s!\n\00")
 (data (i32.const 1056) "Hello\00")
 (data (i32.const 1072) "world\00")
 (export "main" (func $main))
 (func $main (param $0 i32) (param $1 i32) (result i32)
  (local $2 i32)
  (i32.store offset=1024
   (i32.const 0)
   (tee_local $2
    (i32.sub
     (i32.load offset=1024
      (i32.const 0)
     )
     (i32.const 16)
    )
   )
  )
  (i32.store offset=12
   (get_local $2)
   (get_local $0)
  )
  (i32.store offset=8
   (get_local $2)
   (get_local $1)
  )
  (i32.store offset=4
   (get_local $2)
   (i32.const 1072)
  )
  (i32.store
   (get_local $2)
   (i32.const 1056)
  )
  (drop
   (call $printf
    (i32.const 1040)
    (get_local $2)
   )
  )
  (i32.store offset=1024
   (i32.const 0)
   (i32.add
    (get_local $2)
    (i32.const 16)
   )
  )
  (i32.const 0)
 )
)
;; METADATA: { "asmConsts": {},"staticBump": 54, "initializers": [] }