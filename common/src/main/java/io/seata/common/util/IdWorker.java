package io.seata.common.util;

import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ID生成器：基于改良版雪花算法
 *
 * @author funkye
 * @author selfishlover
 */
public class IdWorker {

    /** 起始时间戳 (2020-05-03) */
    private final long twepoch = 1588435200000L;

    /** 机器号（工作节点ID）位数、时间戳位数、序列号位数、最大机器号ID（1023） */
    private final int workerIdBits = 10;
    private final int timestampBits = 41;
    private final int sequenceBits = 12;
    private final int maxWorkerId = ~(-1 << workerIdBits);

    /**
     * 机器号ID
     * business meaning: machine ID (0 ~ 1023)
     * actual layout in memory:
     * highest 1 bit: 0
     * middle 10 bit: workerId
     * lowest 53 bit: all 0
     */
    private long workerId;

    /**
     * 时间戳&序列号
     * timestamp and sequence mix in one Long
     * highest 11 bit: not used
     * middle  41 bit: timestamp
     * lowest  12 bit: sequence
     */
    private AtomicLong timestampAndSequence;

    /**
     * 时间戳&序列号掩码
     */
    private final long timestampAndSequenceMask = ~(-1L << (timestampBits + sequenceBits));

    /** 构造方法 */
    public IdWorker(Long workerId) {
        initTimestampAndSequence();
        initWorkerId(workerId);
    }

    /** 初始化时间戳&序列号 */
    private void initTimestampAndSequence() {
        long timestamp = getNewestTimestamp();
        long timestampWithSequence = timestamp << sequenceBits;
        this.timestampAndSequence = new AtomicLong(timestampWithSequence);
    }

    /** 获取最新的时间戳：now - 起始时间戳 */
    private long getNewestTimestamp() {
        return System.currentTimeMillis() - twepoch;
    }

    /** 初始化机器号 */
    private void initWorkerId(Long workerId) {
        if (workerId == null) {
            workerId = generateWorkerId();
        }
        if (workerId > maxWorkerId || workerId < 0) {
            String message = String.format("worker Id can't be greater than %d or less than 0", maxWorkerId);
            throw new IllegalArgumentException(message);
        }
        this.workerId = workerId << (timestampBits + sequenceBits);
    }

    /** 生成机器号ID */
    private long generateWorkerId() {
        try {
            return generateWorkerIdBaseOnMac();
        } catch (Exception e) {
            return generateRandomWorkerId();
        }
    }

    /** 基于MAC地址生成机器号ID */
    private long generateWorkerIdBaseOnMac() throws Exception {
        Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
        while (all.hasMoreElements()) {
            NetworkInterface networkInterface = all.nextElement();
            boolean isLoopback = networkInterface.isLoopback();
            boolean isVirtual = networkInterface.isVirtual();
            if (isLoopback || isVirtual) {
                continue;
            }
            byte[] mac = networkInterface.getHardwareAddress();
            return ((mac[4] & 0B11) << 8) | (mac[5] & 0xFF);
        }
        throw new RuntimeException("no available mac found");
    }

    /** 生成随机的工作机器号（工作节点ID） */
    private long generateRandomWorkerId() {
        return new Random().nextInt(maxWorkerId + 1);
    }

    /**
     * 获取下一个ID
     * get next UUID(base on snowflake algorithm), which look like:
     * highest 1 bit: always 0
     * next   10 bit: workerId
     * next   41 bit: timestamp
     * lowest 12 bit: sequence
     * @return UUID
     */
    public long nextId() {
        // 改良版的关键：时间戳&序列号作为一个整体，作为低53位
        waitIfNecessary();
        long next = timestampAndSequence.incrementAndGet();
        long timestampWithSequence = next & timestampAndSequenceMask;
        return workerId | timestampWithSequence;
    }

    /**
     * block current thread if the QPS of acquiring UUID is too high
     * that current sequence space is exhausted
     */
    private void waitIfNecessary() {
        long currentWithSequence = timestampAndSequence.get();
        long current = currentWithSequence >>> sequenceBits;
        long newest = getNewestTimestamp();
        if (current >= newest) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException ignore) {
                // don't care
            }
        }
    }

}
