package com.yujian.im.config;

import com.yujian.im.common.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 雪花 ID 装配成单例 Bean。
 *
 * <p>workerId 用 {@link SnowflakeIdGenerator} 的构造参数注入，本机固定；
 * 多实例部署时应该从配置中心或 Redis 抢号段来分配，避免两个进程撞号。
 */
@Configuration
public class IdConfig {

    @Value("${im.snowflake.datacenter-id:1}")
    private long datacenterId;

    @Value("${im.netty.worker-threads:0}")
    private int workerThreads;

    @Bean
    public SnowflakeIdGenerator idGenerator() {
        // 单机场景：workerId 用 0。部署多实例时改成从环境变量读，每个实例不同
        long workerId = Long.parseLong(System.getenv().getOrDefault("IM_WORKER_ID", "0"));
        return new SnowflakeIdGenerator(workerId, datacenterId);
    }
}
