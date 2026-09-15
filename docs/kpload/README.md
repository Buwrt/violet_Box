# kpload —— KernelPatch supercall 工具

一个 4377 字节的 freestanding aarch64 静态 ELF，只做一件事：

```
syscall(45, superkey, ver_and_cmd(SUPERCALL_KPM_LOAD), path, args, NULL)
```

然后把返回值按十进制打到 stdout，再 `exit_group(0)`。

对应 FolkPatch `apd/src/supercall.rs` 里的 `SUPERCALL_KPM_LOAD`：

```rust
const SUPERCALL_KPM_LOAD: c_long = 0x1020;
ver_and_cmd(cmd) = ((major<<16 | minor<<8 | patch) << 32) | (0x1158 << 16) | (cmd & 0xFFFF)
```

## 为什么长这样

构建环境既没有 Android NDK，也没有 aarch64 交叉链接器。所以：

1. `kpload.S` 手写汇编，只用 `svc #0`，不碰 libc；
2. 用 clang 汇编到 `.o`（不需要链接器），`llvm-objcopy` 抽出 `.text` 裸机器码；
3. `mkelf.py` 手工拼一个最小 ELF64（一个 `PT_LOAD`，R+X）。

## 复现

```bash
clang --target=aarch64-linux-gnu -c -o kpload.o kpload.S
llvm-objcopy -O binary --only-section=.text kpload.o kpload.bin
python3 mkelf.py kpload.bin kpload.arm64        # -> app/src/main/assets/kpload.arm64
```

## 自测（需要 qemu-user-static）

```bash
qemu-aarch64-static ./kpload.arm64 k 1103827430809600 /tmp/x.kpm ''  # 打印 syscall 返回值
qemu-aarch64-static ./kpload.arm64 k                                 # -22 (EINVAL)，参数不足
```

模拟器没有 KernelPatch，所以会返回它自己对 45 号调用的返回值；这里验证的是
参数解析、syscall 发出、返回值回传这三段链路。

## 安全边界

不读文件、不写文件、不联网、不 fork。唯一的输出是那个返回码。
