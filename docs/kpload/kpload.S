    .text
    .globl _start

// KernelPatch supercall invoker (freestanding aarch64, no libc).
//   argv[1] = superkey   argv[2] = ver_and_cmd (decimal)  argv[3] = path  argv[4] = args
// Prints the raw return code as decimal on stdout, then exits 0.
_start:
    mov     x29, #0
    mov     x30, #0
    ldr     x0, [sp]                // argc
    add     x1, sp, #8              // argv
    bl      main
    mov     x19, x0                 // rc

    // ---- emit rc as decimal, digits backwards into a stack buffer ----
    sub     sp, sp, #48
    add     x14, sp, #47            // one past last byte
    mov     x15, #0                 // digit count
    mov     x12, x19
    cmp     x12, #0
    b.ge    1f
    neg     x12, x12
1:
    mov     x16, #10
    udiv    x17, x12, x16
    msub    x18, x17, x16, x12      // x18 = x12 % 10
    add     w18, w18, #0x30
    strb    w18, [x14]
    sub     x14, x14, #1
    add     x15, x15, #1
    mov     x12, x17
    cbnz    x12, 1b
    cmp     x19, #0
    b.ge    2f
    mov     w13, #'-'
    strb    w13, [x14]
    sub     x14, x14, #1
    add     x15, x15, #1
2:
    add     x1, x14, #1             // buf
    mov     x2, x15                 // len
    mov     w0, #1                  // stdout
    mov     x8, #64                 // __NR_write (aarch64)
    svc     #0

    mov     x0, #0
    mov     x8, #94                 // __NR_exit_group
    svc     #0

// long main(int argc, char **argv)
main:
    stp     x29, x30, [sp, #-32]!
    mov     x29, sp
    str     x1, [sp, #24]           // argv

    cmp     w0, #5
    b.lt    9f                      // need at least 4 args

    ldr     x0, [x1, #16]           // argv[2] -> decimal cmd
    bl      atol
    mov     x20, x0

    ldr     x1, [sp, #24]
    ldr     x0, [x1, #8]            // argv[1] = superkey
    ldr     x2, [x1, #24]           // argv[3] = path
    ldr     x3, [x1, #32]           // argv[4] = args
    mov     x4, #0
    mov     x8, #45                 // __NR_supercall
    mov     x1, x20                 // ver_and_cmd
    svc     #0
    b       8f
9:
    mov     x0, #-22                // -EINVAL
8:
    ldp     x29, x30, [sp], #32
    ret

// unsigned long atol(const char *s)
atol:
    mov     x9, #0
1:
    ldrb    w11, [x0], #1
    cbz     w11, 2f
    cmp     w11, #0x30
    b.lt    2f
    cmp     w11, #0x39
    b.gt    2f
    sub     w11, w11, #0x30
    mov     x12, #10
    mul     x9, x9, x12
    add     x9, x9, x11
    b       1b
2:
    mov     x0, x9
    ret
