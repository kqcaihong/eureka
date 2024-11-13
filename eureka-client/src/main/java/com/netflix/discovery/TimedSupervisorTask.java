package com.netflix.discovery;

import com.netflix.servo.monitor.Counter;
import com.netflix.servo.monitor.LongGauge;
import com.netflix.servo.monitor.MonitorConfig;
import com.netflix.servo.monitor.Monitors;
import java.util.TimerTask;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A supervisor task that schedules subtasks while enforce a timeout.
 * Wrapped subtasks must be thread safe.
 *
 * @author David Qiang Liu
 */
//作用在于：
//    - 对任务超时、被拒绝、执行异常，提供了数量统计与监控；
//    - 异步执行任务体，使用DiscoveryClient中的heartbeatExecutor线程池；
//    - 对超时时间自适应：本次心跳超时后，会将下次心跳的超时时间放大，一旦心跳成功后，将超时时间恢复正常值；
//    - 提交下一次的延迟任务
public class TimedSupervisorTask extends TimerTask {
    private static final Logger logger = LoggerFactory.getLogger(TimedSupervisorTask.class);
    // 执行异常计数
    private final Counter timeoutCounter;
    private final Counter rejectedCounter;
    private final Counter throwableCounter;
    private final LongGauge threadPoolLevelGauge;

    // 调度线程池
    private final ScheduledExecutorService scheduler;
    // 执行心跳的线程池
    private final ThreadPoolExecutor executor;
    // 超时时间
    private final long timeoutMillis;
    // heatBeat任务体
    private final Runnable task;

    private final AtomicLong delay;
    // 最大延迟时间
    private final long maxDelay;

    public TimedSupervisorTask(String name, ScheduledExecutorService scheduler, ThreadPoolExecutor executor,
                               int timeout, TimeUnit timeUnit, int expBackOffBound, Runnable task) {
        this.scheduler = scheduler;
        this.executor = executor;
        this.timeoutMillis = timeUnit.toMillis(timeout);
        this.task = task;
        this.delay = new AtomicLong(timeoutMillis);
        this.maxDelay = timeoutMillis * expBackOffBound;

        // Initialize the counters and register.
        timeoutCounter = Monitors.newCounter("timeouts");
        rejectedCounter = Monitors.newCounter("rejectedExecutions");
        throwableCounter = Monitors.newCounter("throwables");
        threadPoolLevelGauge = new LongGauge(MonitorConfig.builder("threadPoolUsed").build());
        Monitors.registerObject(name, this);
    }

    public void run() {
        Future future = null;
        try {
            future = executor.submit(task);
            threadPoolLevelGauge.set((long) executor.getActiveCount());
            future.get(timeoutMillis, TimeUnit.MILLISECONDS);  // block until done or timeout
            // 未超时，重置延迟时间为正常值
            delay.set(timeoutMillis);
            threadPoolLevelGauge.set((long) executor.getActiveCount());
        } catch (TimeoutException e) {
            logger.error("task supervisor timed out", e);
            timeoutCounter.increment();

            // 增大延迟时间为两倍
            long currentDelay = delay.get();
            long newDelay = Math.min(maxDelay, currentDelay * 2);
            delay.compareAndSet(currentDelay, newDelay);

        } catch (RejectedExecutionException e) {
            if (executor.isShutdown() || scheduler.isShutdown()) {
                logger.warn("task supervisor shutting down, reject the task", e);
            } else {
                logger.error("task supervisor rejected the task", e);
            }

            rejectedCounter.increment();
        } catch (Throwable e) {
            if (executor.isShutdown() || scheduler.isShutdown()) {
                logger.warn("task supervisor shutting down, can't accept the task");
            } else {
                logger.error("task supervisor threw an exception", e);
            }

            throwableCounter.increment();
        } finally {
            // 为何要取消？
            if (future != null) {
                future.cancel(true);
            }
            // 添加下次延迟任务
            if (!scheduler.isShutdown()) {
                scheduler.schedule(this, delay.get(), TimeUnit.MILLISECONDS);
            }
        }
    }
}