package com.yujian.im.common;

import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/**
 * 把 Java 的 {@code Long} 统一序列化成 JSON 字符串。
 *
 * <p><b>为什么必须要有这个类（面试真事）：</b>
 * 本项目主键是雪花 ID，19 位十进制，量级 ~3.8e17。而 JavaScript 的
 * {@code Number} 只能精确表示 2^53-1（约 9.007e15）以内的整数 ——
 * 雪花 ID 早就超了。浏览器拿到 {@code 383197437298343936} 会存成
 * {@code 383197437298343940}（尾部几位被"就近舍入"掉）。
 *
 * <p>症状非常隐蔽：登录正常、会话列表正常显示，但一发消息就报
 * "你不在该会话中"，因为前端拿错误的 convId 去打 {@code assertMember}。
 * 抓包看 HTTP 响应是 {@code 383197437298343936}，看浏览器 console 却是
 * {@code ...940}，两边对不上。这就是本项目真实踩到的坑。
 *
 * <p><b>为什么用全局 Module 而不是给每个字段加注解：</b>
 * 注解方案要在每个 {@code Long id} 上写 {@code @JsonSerialize(using = ToStringSerializer.class)}，
 * 漏一个字段就是一个线上 bug，而且以后新增接口还会漏。全局 Module 是"约定优于配置"，
 * 一次注册，所有出口（HTTP 的 Spring MVC、长连接的 {@code BodyCodec}）都生效。
 *
 * <p>只注册包装类型 {@code Long}，不碰原始类型 {@code long}：
 * {@code seq}、{@code serverTime} 这类"计数器/时间戳"语义上就是数字，保持数字更好用；
 * 而 ID 语义上是个"标识符"，是字符串更合适（前端也从不拿它做算术）。
 */
public final class JsonModules {

    public static final SimpleModule LONG_TO_STRING =
            new SimpleModule("long-to-string")
                    .addSerializer(Long.class, ToStringSerializer.instance);

    private JsonModules() {
    }
}
