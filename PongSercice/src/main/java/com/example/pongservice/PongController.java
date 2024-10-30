package com.example.pongservice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.Semaphore;

@RestController
public class PongController {

    private static final Logger logger = LoggerFactory.getLogger(PongController.class);
    /**
     *  每秒1个请求限制
     */
    private static final Semaphore RATE_LIMITER = new Semaphore(1);

    @GetMapping("/ping")
    public Mono<ResponseEntity<String>> handlePing() {
        logResult(" ");
        if (RATE_LIMITER.tryAcquire()) {
            // 延迟1秒以模拟工作负载
            return Mono.delay(Duration.ofSeconds(1))
                    .map(i -> {
                        RATE_LIMITER.release();
                        return ResponseEntity.ok("World");
                    });
        } else {
            return Mono.just(ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build());
        }
    }

    private void logResult(String message) {
        logger.info("PongService received the request - {}: {}", LocalDateTime.now(), message);
    }
}