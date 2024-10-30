package com.example.pongservice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(SpringExtension.class)
@WebFluxTest(PongController.class)
@Import(PongController.class)
public class PongControllerTest {

    @Autowired
    private WebTestClient webTestClient;


    @BeforeEach
    public void setup() {
        webTestClient = webTestClient.mutate()
                .responseTimeout(Duration.ofSeconds(2))
                .build();
    }

    @Test
    public void testHandlePing_Success() {
        // 测试第一个请求是否成功
        webTestClient.get().uri("/ping")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(response -> assertEquals("World", response));
    }

    @Test
    public void testHandlePing_WithConcurrentRequests() throws InterruptedException {
        int totalRequests = 5; // 设置总请求数
        CountDownLatch latch = new CountDownLatch(totalRequests); // 倒计时锁
        ExecutorService executorService = Executors.newFixedThreadPool(totalRequests);

        for (int i = 0; i < totalRequests; i++) {
            executorService.submit(() -> {
                try {
                    webTestClient.get().uri("/ping")
                            .exchange()
                            .expectStatus().value(status -> {
                                if (status.equals(HttpStatus.OK.value())) {
                                    System.out.println("Request succeeded with status 200 OK");
                                } else if (status.equals(HttpStatus.TOO_MANY_REQUESTS.value())) {
                                    System.out.println("Request rate-limited with status 429 TOO_MANY_REQUESTS");
                                }
                            });
                } finally {
                    latch.countDown();
                }
            });
        }

        // 等待所有线程完成
        latch.await();
        executorService.shutdown();
    }

    @Test
    public void testHandlePing_WithDelay() throws InterruptedException {
        // 第一个请求应当成功
        webTestClient.get().uri("/ping")
                .exchange()
                .expectStatus().isOk();

        // 等待 1 秒后再发送请求，模拟速率限制的恢复
        Thread.sleep(1000);

        webTestClient.get().uri("/ping")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(response -> assertEquals("World", response));
    }
}

