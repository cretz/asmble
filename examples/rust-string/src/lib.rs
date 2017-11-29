#![feature(allocator_api)]

use std::heap::{Alloc, Heap, Layout};
use std::ffi::{CString};
use std::mem;
use std::os::raw::c_char;
use std::str;

#[no_mangle]
pub extern "C" fn string_len(ptr: *mut u8, len: usize) -> usize {
    unsafe {
        let bytes = Vec::<u8>::from_raw_parts(ptr, len, len);
        let len = str::from_utf8(&bytes).unwrap().chars().count();
        mem::forget(bytes);
        len
    }
}

#[no_mangle]
pub extern "C" fn prepend_from_rust(ptr: *mut u8, len: usize) -> *const c_char {
    unsafe {
        let bytes = Vec::<u8>::from_raw_parts(ptr, len, len);
        let s = str::from_utf8(&bytes).unwrap();
        mem::forget(s);
        let cstr = CString::new(format!("From Rust: {}", s)).unwrap();
        let ret = cstr.as_ptr();
        mem::forget(cstr);
        return ret
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