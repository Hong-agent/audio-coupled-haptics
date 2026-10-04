package com.gaolou.boneconduction.engine;

import java.util.ArrayDeque;
import java.util.function.IntSupplier;

/**
 * 有界阻塞队列：满载时丢弃最旧元素而不是阻塞生产者（规格书决策 6）。
 * 容量通过 IntSupplier 动态读取，因此队列上限滑杆改动即时生效。
 */
public final class BoundedQueue<T> {

    private final ArrayDeque<T> deque = new ArrayDeque<>();
    private final IntSupplier capacity;
    private final Object lock = new Object();
    private long dropped;

    public BoundedQueue(IntSupplier capacity) {
        this.capacity = capacity;
    }

    public void offer(T value) {
        synchronized (lock) {
            int cap = Math.max(1, capacity.getAsInt());
            while (deque.size() >= cap) {
                deque.pollFirst();
                dropped++;
            }
            deque.addLast(value);
            lock.notifyAll();
        }
    }

    /** 队首元素；超时返回 null。 */
    public T poll(long timeoutMs) {
        synchronized (lock) {
            if (deque.isEmpty() && timeoutMs > 0) {
                try {
                    lock.wait(timeoutMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            return deque.pollFirst();
        }
    }

    public T poll() {
        synchronized (lock) {
            return deque.pollFirst();
        }
    }

    public int size() {
        synchronized (lock) {
            return deque.size();
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return deque.isEmpty();
        }
    }

    public long droppedCount() {
        synchronized (lock) {
            return dropped;
        }
    }

    public void clear() {
        synchronized (lock) {
            deque.clear();
        }
    }

    /** 唤醒所有等待者（停止时用）。 */
    public void wakeAll() {
        synchronized (lock) {
            lock.notifyAll();
        }
    }
}
