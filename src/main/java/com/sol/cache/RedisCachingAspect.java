package com.sol.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import com.sol.cache.annotation.Cached;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Aspect
@Component
@RequiredArgsConstructor
public class RedisCachingAspect {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExpressionParser spelParser = new SpelExpressionParser();

    @Value("${spring.profiles.active:default}")
    private String activeProfile;

    @Value("${app.cache.default-ttl-seconds:3600}")
    private long defaultTtl;

    @Around("@annotation(cached)")
    public Object around(ProceedingJoinPoint pjp, Cached cached) throws Throwable {
        final String key = buildKey(pjp, cached);
        final ValueOperations<String, Object> ops = redisTemplate.opsForValue();

        MethodSignature ms = (MethodSignature) pjp.getSignature();
        Class<?> returnType = ms.getReturnType();

        // ---------- Mono<T> ----------
        Object cachedVal = ops.get(key);
        if (Mono.class.isAssignableFrom(returnType)) {
            if (cachedVal != null) {
                return Mono.just(cachedVal);
            }

            @SuppressWarnings("unchecked")
            Mono<Object> mono = (Mono<Object>) pjp.proceed();

            // Only cache when profile matches
            if (!profileAllowsCaching(cached))
                return mono;

            long ttl = ttlSeconds(cached);
            return mono.doOnNext(v -> {
                if (v != null)
                    ops.set(key, v, Duration.ofSeconds(ttl));
            });
        } else if (Flux.class.isAssignableFrom(returnType)) {
            if (cachedVal instanceof List<?> list) {
                return Flux.fromIterable(list);
            }

            @SuppressWarnings("unchecked")
            Flux<Object> flux = (Flux<Object>) pjp.proceed();

            if (!profileAllowsCaching(cached))
                return flux;

            long ttl = ttlSeconds(cached);
            return flux.collectList().doOnNext(list -> {
                if (list != null)
                    ops.set(key, list, Duration.ofSeconds(ttl));
            }).flatMapMany(Flux::fromIterable);
        } else if (cachedVal != null) {
            if (returnType.isInstance(cachedVal)) {
                return cachedVal;
            }

            try {
                return convertToReturnType(cachedVal, ms, returnType);
            } catch (Exception ignore) {
                // If conversion fails, fall through and compute fresh
            }
        }



        // ---------- Synchronous ----------
        Object result = pjp.proceed();
        if (result != null && profileAllowsCaching(cached)) {
            ops.set(key, result, Duration.ofSeconds(ttlSeconds(cached)));
        }
        return result;
    }

    // ----------------- helpers -----------------

    private boolean profileAllowsCaching(Cached cached) {
        String required = cached.activeProfile(); // e.g. "*" or "local"
        return "*".equals(required) || activeProfile.contains(required);
    }

    private Object convertToReturnType(Object value, MethodSignature ms, Class<?> returnType) {
        JavaType javaType =
                mapper.getTypeFactory().constructType(ms.getMethod().getGenericReturnType());
        return mapper.convertValue(value, javaType);
    }

    private long ttlSeconds(Cached cached) {
        return cached.ttlSeconds() > 0 ? cached.ttlSeconds() : defaultTtl;
    }

    /** Build cache key using SpEL if provided; otherwise hash method + args for a stable key. */
    private String buildKey(ProceedingJoinPoint pjp, Cached cached) throws Exception {
        String prefix = cached.cacheName() + "::";

        if (!cached.key().isBlank()) {
            Expression expression = spelParser.parseExpression(cached.key());
            EvaluationContext ctx = buildEvaluationContext(pjp);
            String keyValue = expression.getValue(ctx, String.class);
            return prefix + keyValue;
        }

        MethodSignature ms = (MethodSignature) pjp.getSignature();
        String methodId = ms.getDeclaringTypeName() + "#" + ms.getName();
        String argsJson = mapper.writeValueAsString(pjp.getArgs());
        String digest = sha256Hex(argsJson);
        return prefix + methodId + "::" + digest;
    }

    private EvaluationContext buildEvaluationContext(ProceedingJoinPoint pjp) {
        MethodSignature ms = (MethodSignature) pjp.getSignature();
        String[] paramNames = ms.getParameterNames();
        Object[] args = pjp.getArgs();

        StandardEvaluationContext ctx = new StandardEvaluationContext();
        if (paramNames != null) {
            for (int i = 0; i < paramNames.length; i++) {
                ctx.setVariable(paramNames[i], args[i]);
            }
        } else {
            // Fallback: expose args as #p0, #p1...
            for (int i = 0; i < args.length; i++) {
                ctx.setVariable("p" + i, args[i]);
            }
        }
        // Also provide an #args array if needed in SpEL
        ctx.setVariable("args", Arrays.asList(args));
        return ctx;
    }

    private static String sha256Hex(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes)
            sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
