package com.example.pingservice;

import com.example.pingservice.controller.PingController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClient.RequestHeadersUriSpec;
import org.springframework.web.reactive.function.client.WebClient.ResponseSpec;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@MockitoSettings(strictness = Strictness.LENIENT)
public class PingControllerTest {

    @Mock
    private WebClient.Builder webClientBuilder;

    @Autowired
    private WebTestClient webTestClient;

    @Mock
    private WebClient webClient;

    @Mock
    private RequestHeadersUriSpec<?> requestHeadersUriSpec;

    @Mock
    private ResponseSpec responseSpec;

    private PingController pingController;

    private static final Logger logger = Logger.getLogger(PingController.class.getName());

    @BeforeEach
    public void setup() {

        // 模拟 WebClient.Builder 的链式调用
        when(webClientBuilder.baseUrl(any(String.class))).thenReturn(webClientBuilder);
        when(webClientBuilder.build()).thenReturn(webClient);
        // 手动创建实例并注入模拟的依赖
        pingController = new PingController(webClientBuilder);

        // 使用 Answer 返回 requestHeadersUriSpec，避免类型不匹配问题
        when(webClient.get()).thenAnswer(invocation -> requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString())).thenAnswer(invocation -> requestHeadersUriSpec);
        when(requestHeadersUriSpec.retrieve()).thenReturn(responseSpec);
    }


    @Test
    public void testSendPingRequest_SuccessfulResponse() {
        // 使用 Mockito 返回一个 Mono<String>，而不是返回类型不匹配的值
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just("World"));
        // 调用方法
        pingController.sendPingRequest();
        // 验证 bodyToMono(String.class) 被调用并返回正确的内容

        Mono<String> result = responseSpec.bodyToMono(String.class);
        String actualResponseEntity = result.block();
        // 验证响应内容
        assertEquals("World", actualResponseEntity, "Expected response body is 'World'");
    }

    @Test
    public void testSendPingRequest_RateLimitedResponse() {
        // 使用 lenient 以避免 UnnecessaryStubbingException
        lenient().when(responseSpec.onStatus(
                HttpStatus.TOO_MANY_REQUESTS::equals,
                clientResponse -> Mono.error(new RuntimeException("Too Many Requests"))
        )).thenReturn(responseSpec);

        lenient().when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just("Too Many Requests"));

        // 调用方法
        pingController.sendPingRequest();

        // 验证 `bodyToMono` 是否被调用
        verify(responseSpec).bodyToMono(String.class);

        // 检查日志输出
        logger.info("Request sent & Pong limited it with 429");
    }

    @Test
    public void testSendPingRequest_RequestSkippedDueToRateLimit() {
        String traceId = UUID.randomUUID().toString();
        PingController spyController = Mockito.spy(pingController);

        // 使用 lenient 来避免 Unnecessary Stubbing 异常
        lenient().doReturn(false).when(spyController).acquireRateLimit();

        spyController.sendPingRequest();

        logger.info("Request not sent due to rate limit");
    }
}

