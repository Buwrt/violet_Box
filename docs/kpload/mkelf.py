#!/usr/bin/env python3
"""把裸 .text 机器码包成一个最小 aarch64 静态 ELF 可执行文件。

没有 NDK / 交叉链接器，所以这里手工拼 ELF64 + 一个 PT_LOAD。
代码是 freestanding 的（只用 svc #0），不需要任何重定位或动态链接。
"""
import struct
import sys

VADDR = 0x400000
CODE_OFF = 0x1000          # 代码在文件里的偏移，也是相对 VADDR 的偏移


def build(code: bytes, out: str) -> int:
    ehdr = bytearray(64)
    ehdr[0:16] = bytes([0x7F, 0x45, 0x4C, 0x46,  # \x7fELF
                        2,                        # EI_CLASS = ELFCLASS64
                        1,                        # EI_DATA  = ELFSDATA2LSB
                        1,                        # EI_VERSION
                        0,                        # EI_OSABI = SYSV
                        0]) + bytes(8)            # EI_PAD
    struct.pack_into('<HHIQQQIHHHHHH', ehdr, 16,
                     2,        # e_type    = ET_EXEC
                     183,      # e_machine = EM_AARCH64
                     1,        # e_version
                     VADDR + CODE_OFF,   # e_entry
                     64,       # e_phoff
                     0,        # e_shoff
                     0,        # e_flags
                     64,       # e_ehsize
                     56,       # e_phentsize
                     1,        # e_phnum
                     64,       # e_shentsize
                     0,        # e_shnum
                     0)        # e_shstrndx

    total = CODE_OFF + len(code)
    phdr = struct.pack('<IIQQQQQQ',
                       1,                 # p_type  = PT_LOAD
                       5,                 # p_flags = PF_R | PF_X
                       0,                 # p_offset
                       VADDR,             # p_vaddr
                       VADDR,             # p_paddr
                       total,             # p_filesz
                       total,             # p_memsz
                       0x1000)            # p_align

    buf = bytearray(total)
    buf[0:64] = ehdr
    buf[64:64 + 56] = phdr
    buf[CODE_OFF:CODE_OFF + len(code)] = code
    with open(out, 'wb') as f:
        f.write(buf)
    return total


if __name__ == '__main__':
    src, dst = sys.argv[1], sys.argv[2]
    with open(src, 'rb') as f:
        c = f.read()
    n = build(c, dst)
    print(f'{dst}: {n} bytes (code {len(c)})')
