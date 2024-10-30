package com.example.pingservice.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

@Component
public class PingController {

    private static final Logger logger = LoggerFactory.getLogger(PingController.class);

    private WebClient webClient;

    /**
     * 计数器文件路径
     */
    private static final Path RATE_LIMIT_FILE = Paths.get("rate-limit.lock");

    private static final int RPS_LIMIT = 2;
    /**
     * 当前机器的唯一 ID
     */
    @Value("${machineId}")
    private int machineId;
    /**
     * 总机器数量
     */
    @Value("${totalMachines}")
    private int totalMachines;
    private int windowSize;
    private int startWindow;

    private ScheduledExecutorService scheduler;

    public PingController() {
        this.windowSize = 1000 / totalMachines;
        this.startWindow = (machineId - 1) * windowSize;
    }

    /**
     * Pong服务地址
     */
    @Autowired
    public PingController(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.baseUrl("http://localhost:8081").build();
    }

    /**
     * 多机器部署在启动时，请求会同时发出，这会导致部分pingService的请求永远无法请求到pongService
     * 尝试过重试的方式，但重试会导致大量线程堆积影响应用性能和响应
     * 故采用分配请求时间窗口的方式，为每个进程分配一个时间窗口，使得在任何一秒内不同进程的请求在不同的时间点发出。
     *
     */
    @PostConstruct
    public void initScheduler() {
        // 使用 ThreadFactory 创建自定义调度线程池
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("PingScheduler-Machine-" + machineId);
            thread.setDaemon(true);
            return thread;
        };

        // 使用 ScheduledThreadPoolExecutor 初始化线程池
        scheduler = new ScheduledThreadPoolExecutor(1, threadFactory);

        // 启动调度任务，初始延迟为 startWindow,根据当前机器可调度的时间延迟执行，避免多机器情况下请求都由部分机器发起
        scheduler.scheduleAtFixedRate(this::sendPingRequest, startWindow, 1000, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void shutdownScheduler() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    public void sendPingRequest() {
        // 为每个请求生成唯一的 traceId
        String traceId = UUID.randomUUID().toString();

        if (acquireRateLimit()) {
            webClient.get().uri("/ping")
                    .retrieve().bodyToMono(String.class)
                    .doOnRequest(request -> logResult("PingService sent: hello", traceId))
                    .doOnError(error -> logResult("PongService error :" + error.getMessage(), traceId))
                    .subscribe(response -> logResult(" PongService response :" + response, traceId));
        } else {
            logResult("Request not sent due to rate limit", traceId);
        }
    }

    public boolean acquireRateLimit() {
        try (FileChannel lockChannel = FileChannel.open(RATE_LIMIT_FILE,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE)) {
            FileLock lock = lockChannel.tryLock();

            try (FileChannel rateChannel = FileChannel.open(RATE_LIMIT_FILE,
                    StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {

                ByteBuffer buffer = ByteBuffer.allocate(16);
                buffer.clear();

                // 读取时间戳和计数器
                if (rateChannel.read(buffer) > 0) {
                    buffer.flip();
                    long lastTimestamp = buffer.getLong();
                    int count = buffer.getInt();

                    // 获取当前时间戳（单位为秒）
                    long currentTimestamp = System.currentTimeMillis() / 1000;

                    // 如果已经过了1秒，重置时间戳和计数
                    if (currentTimestamp - lastTimestamp >= 1) {
                        lastTimestamp = currentTimestamp;
                        count = 0;
                    }

                    // 检查是否达到速率限制
                    if (count < RPS_LIMIT) {
                        count++;
                        buffer.clear();
                        buffer.putLong(lastTimestamp);
                        buffer.putInt(count);
                        buffer.flip();
                        rateChannel.position(0);
                        rateChannel.write(buffer);
                        // 强制写入磁盘
                        rateChannel.force(true);
                        return true;
                    } else {
                        logger.info("Rate limit reached, current count: {}, traceId: {}", count);
                    }
                } else {
                    // 初始情况，没有文件内容时写入默认时间戳和计数
                    long currentTimestamp = Instant.now().getEpochSecond();
                    buffer.clear();
                    buffer.putLong(currentTimestamp);
                    buffer.putInt(1);
                    buffer.flip();
                    rateChannel.write(buffer);
                    rateChannel.force(true);
                    logger.info("First request allowed, traceId: {}");
                    return true;
                }
            } finally {
                lock.release();
            }
        } catch (IOException e) {
            logger.error("Error acquiring rate limit lock", e);
        }
        return false;
    }

    private void logResult(String message, String traceId) {
        logger.info("PingService tid: {} - {}: {}", traceId, LocalDateTime.now(), message);
    }
}