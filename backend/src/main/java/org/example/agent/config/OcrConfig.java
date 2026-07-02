package org.example.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
public class OcrConfig {

    /**
     * OCR 专用线程池：控制并行 OCR 的并发数，避免大量文件同时 base64 编码撑爆内存。
     * 核心 4 线程，最大 8 线程，空闲 60s 回收。
     * 队列无界 + CallerRunsPolicy：队列堆积时调用线程自己跑，形成天然背压。
     */
    @Bean("ocrExecutor")
    public ExecutorService ocrExecutor() {
        return new ThreadPoolExecutor(
                4,
                8,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, "ocr-worker");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
}
