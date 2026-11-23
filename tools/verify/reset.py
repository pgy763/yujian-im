"""
清库脚本：把 MySQL 的四张表和 Redis 的当前 db 清空，回到"干净初始状态"。

为什么不用 subprocess 的 capture_output：沙箱下 CreatePipe 会被拒绝
（PermissionError: [WinError 5]），所以子进程直接继承父进程的 stdio，
输出会打到当前控制台。Redis 用裸 socket 发 FLUSHDB，避免依赖 redis-cli
（cygwin 版 redis-cli 在沙箱里初始化 token 会失败）。
"""

import subprocess
import socket

MYSQL = r"C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe"

SQL = (
    "SET FOREIGN_KEY_CHECKS=0;"
    "TRUNCATE im_message;"
    "TRUNCATE im_conversation_member;"
    "TRUNCATE im_conversation;"
    "TRUNCATE im_friend;"
    "TRUNCATE im_user;"
    "SET FOREIGN_KEY_CHECKS=1;"
)

rc = subprocess.run([MYSQL, "-uroot", "-p123456", "yujian_im", "-e", SQL]).returncode
print("mysql rc =", rc)

with socket.create_connection(("127.0.0.1", 6379), timeout=3) as s:
    s.sendall(b"FLUSHDB\r\n")
    print("redis flushdb ->", s.recv(64).decode(errors="replace").strip())
