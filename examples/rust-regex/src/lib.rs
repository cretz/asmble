#![feature(allocator_api)]

extern crate regex;

use regex::Regex;
use std::heap::{Alloc, Heap, Layout};
use std::mem;
use std::str;

#[no_mangle]
pub extern "C" fn compile_pattern(str_ptr: *mut u8, len: usize) -> *mut Regex {
    unsafe {
        let bytes = Vec::<u8>::from_raw_parts(str_ptr, len, len);
        let s = str::from_utf8_unchecked(&bytes);
        let r = Box::new(Regex::new(s).unwrap());
        Box::into_raw(r)
    }
}

#[no_mangle]
pub extern "C" fn dispose_pattern(r: *mut Regex) {
    unsafe {
        let _r = Box::from_raw(r);
    }
}

#[no_mangle]
pub extern "C" fn match_count(r: *mut Regex, str_ptr: *mut u8, len: usize) -> usize {
    unsafe {
        let bytes = Vec::<u8>::from_raw_parts(str_ptr, len, len);
        let s = str::from_utf8_unchecked(&bytes);
        let r = Box::from_raw(r);
        let count = r.find_iter(s).count();
        mem::forget(r);
        count
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