package com.yujian.im.config;

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.yujian.im.common.JsonModules;

/**
 * 让 Spring MVC 的 Jackson 也用上"Long 序列化成字符串"的约定。
 *
 * <p>不这么做的话会出现很割裂的现象：HTTP 接口返回的 id 是数字（前端精度丢了），
 * 而长连接推送里的 id 是字符串（因为 {@code BodyCodec} 用的是我们自己的 ObjectMapper），
 * 同一份数据两种类型，前端要写两套判断。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longToStringCustomizer() {
        return builder -> {
            builder.modules(new JavaTimeModule(), JsonModules.LONG_TO_STRING);
            // 时间统一写成 ISO 字符串，别写成时间戳数字 —— 抓包和排查都直观
            builder.featuresToDisable(
                    com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        };
    }
}
