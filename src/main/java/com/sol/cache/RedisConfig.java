package com.sol.cache;

import org.springframework.context.annotation.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.serializer.*;

@Configuration
public class RedisConfig {

    @Bean
    RedisTemplate<String, Object> redisTemplate(LettuceConnectionFactory cf) {
        RedisTemplate<String, Object> tpl = new RedisTemplate<>();
        tpl.setConnectionFactory(cf);
        var json = new GenericJackson2JsonRedisSerializer();
        tpl.setKeySerializer(new StringRedisSerializer());
        tpl.setValueSerializer(json);
        tpl.setHashKeySerializer(new StringRedisSerializer());
        tpl.setHashValueSerializer(json);
        tpl.afterPropertiesSet();
        return tpl;
    }
}
