package net.caffeinemc.mods.sodium.client.util.collections;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class DoubleBufferedQueue<E> {
    private QueueImpl<E> read, write;

    public DoubleBufferedQueue() {
        // Start with smaller initial capacity and let it grow if needed
        this.read = new QueueImpl<>(64);
        this.write = new QueueImpl<>(64);
    }

    public boolean flip() {
        if (this.write.isEmpty()) {
            return false;
        }

        QueueImpl<E> tmp = this.read;
        this.read = this.write;
        this.write = tmp;

        this.write.reset();

        return true;
    }

    public void reset() {
        this.read.reset();
        this.write.reset();
    }

    public ReadQueue<E> read() {
        return this.read;
    }

    public WriteQueue<E> write() {
        return this.write;
    }

    private static final class QueueImpl<E> implements ReadQueue<E>, WriteQueue<E> {
        private E[] elements;
        private int readIndex;
        private int writeIndex;
        private int mask;  // For fast modulo operations

        @SuppressWarnings("unchecked")
        QueueImpl(int capacity) {
            // Round up to next power of 2 for fast modulo
            int size = 1 << (32 - Integer.numberOfLeadingZeros(capacity - 1));
            this.elements = (E[]) new Object[size];
            this.mask = size - 1;
        }

        @Override
        public void ensureCapacity(int numElements) {
            int required = this.writeIndex + numElements;
            if (required > elements.length) {
                resize(required);
            }
        }

        @Override
        public @Nullable E dequeue() {
            if (readIndex == writeIndex) {
                return null;
            }

            E element = elements[readIndex & mask];
            elements[readIndex & mask] = null; // Help GC
            readIndex++;
            return element;
        }

        @Override
        public void enqueue(@NotNull E e) {
            if (writeIndex - readIndex >= elements.length) {
                resize(elements.length << 1);
            }
            elements[writeIndex & mask] = e;
            writeIndex++;
        }

        public boolean isEmpty() {
            return writeIndex == readIndex;
        }

        public void reset() {
            if (writeIndex > readIndex) {
                // Only clear used portion
                int start = readIndex & mask;
                int end = writeIndex & mask;
                if (start < end) {
                    // Continuous region
                    clearRange(start, end);
                } else {
                    // Wrapped around
                    clearRange(start, elements.length);
                    clearRange(0, end);
                }
            }
            readIndex = writeIndex = 0;
        }

        private void clearRange(int from, int to) {
            while (from < to) {
                elements[from++] = null;
            }
        }

        @SuppressWarnings("unchecked")
        private void resize(int minCapacity) {
            // Round up to next power of 2
            int newSize = 1 << (32 - Integer.numberOfLeadingZeros(minCapacity - 1));
            E[] newElements = (E[]) new Object[newSize];

            // Copy elements, handling wrap-around
            int size = writeIndex - readIndex;
            if (size > 0) {
                int start = readIndex & mask;
                int end = writeIndex & mask;
                if (start < end) {
                    // Continuous region
                    System.arraycopy(elements, start, newElements, 0, size);
                } else {
                    // Wrapped around
                    int firstPart = elements.length - start;
                    System.arraycopy(elements, start, newElements, 0, firstPart);
                    System.arraycopy(elements, 0, newElements, firstPart, end);
                }
            }

            elements = newElements;
            mask = newSize - 1;
            writeIndex = size;
            readIndex = 0;
        }
    }
}