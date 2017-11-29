#![feature(allocator_api)]

extern crate regex;

use regex::Regex;
use std::heap::{Alloc, Heap, Layout};
use std::mem;
use std::str;

#[no_mangle]
pub extern "C" fn compile_pattern(str_ptr: *mut u8, len: usize) -> *const Regex {
    unsafe {
        let bytes = Vec::<u8>::from_raw_parts(str_ptr, len, len);
        let s = str::from_utf8(&bytes).unwrap();
        let r = Regex::new(s).unwrap();
        let raw_r = &r as *const Regex;
        mem::forget(s);
        mem::forget(r);
        raw_r
    }
}

#[no_mangle]
pub extern "C" fn alloc(size: usize) -> *mut u8 {
    unsafe {
        let layout = Layout::from_size_align(size, mem::align_of::<u8>()).unwrap();
        Heap.alloc(layout).unwrap()
    }
}

#[no_mangle]
pub extern "C" fn dealloc(ptr: *mut u8, size: usize) {
    unsafe  {
        let layout = Layout::from_size_align(size, mem::align_of::<u8>()).unwrap();
        Heap.dealloc(ptr, layout);
    }
}