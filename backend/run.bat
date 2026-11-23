@echo off
REM 启动鱼见 IM 后端（Windows 批处理版）
REM 前置条件：MySQL 3306、Redis 6379 已在跑；数据库 yujian_im 已建
cd /d "%~dp0"

if not exist target\yujian-im.jar (
    echo [ERROR] 找不到 target\yujian-im.jar，请先执行 mvn -DskipTests package
    exit /b 1
)

echo 启动中：HTTP 8080 / TCP 9000 / WebSocket 9001
set JAVA_HOME=D:\jdk21
"%JAVA_HOME%\bin\java" -jar target\yujian-im.jar
