package com.yujian.im;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 入口。
 *
 * <p>一个进程同时起两个监听：
 * <ul>
 *   <li>8080 HTTP —— 登录、会话列表、历史消息（Spring MVC，Tomcat 线程池）</li>
 *   <li>9000 TCP / 9001 WebSocket —— 长连接（Netty，EventLoop）</li>
 * </ul>
 *
 * <p>为什么不把长连接也塞进 Tomcat：Tomcat 的模型是"一请求一线程"，
 * 一万个空闲的长连接会占掉一万个线程和它们的栈（默认 1MB/线程），
 * 而 Netty 一个 EventLoop 线程能扛住几千个连接，这就是 NIO 的复用价值。
 */
@SpringBootApplication
@EnableAsync
public class YuJianImApplication {

    public static void main(String[] args) {
        SpringApplication.run(YuJianImApplication.class, args);
    }
}
