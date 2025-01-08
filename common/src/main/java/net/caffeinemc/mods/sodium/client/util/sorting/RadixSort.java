package net.caffeinemc.mods.sodium.client.util.sorting;

public class RadixSort extends AbstractSort {
    public static final int RADIX_SORT_THRESHOLD = 64;

    private static final int DIGIT_BITS = 8;
    // Only sort the top 16 bits (2 passes instead of 3)
    private static final int SIGNIFICANT_BITS = 16;
    private static final int BUCKET_COUNT = 1 << DIGIT_BITS;
    private static final int DIGIT_COUNT = (SIGNIFICANT_BITS + DIGIT_BITS - 1) / DIGIT_BITS;
    private static final int DIGIT_MASK = (1 << DIGIT_BITS) - 1;

    public static int[] sort(int[] keys) {
        if (keys.length <= 1) {
            return new int[keys.length];
        }

        return radixSort(keys, createHistogram(keys));
    }

    private static int[][] createHistogram(int[] keys) {
        var histogram = new int[DIGIT_COUNT][BUCKET_COUNT];

        for (final int key : keys) {
            for (int digit = 0; digit < DIGIT_COUNT; digit++) {
                histogram[digit][extractDigit(key, digit)] += 1;
            }
        }

        return histogram;
    }

    private static void prefixSum(int[][] offsets) {
        for (int digit = 0; digit < DIGIT_COUNT; digit++) {
            final var buckets = offsets[digit];
            var sum = 0;

            for (int bucket_idx = 0; bucket_idx < BUCKET_COUNT; bucket_idx++) {
                final var offset = sum;
                sum += buckets[bucket_idx];
                buckets[bucket_idx] = offset;
            }
        }
    }

    private static int[] radixSort(int[] keys, int[][] offsets) {
        prefixSum(offsets);

        final var length = keys.length;

        int[] cur = createIndexBuffer(length);
        int[] next = new int[length];

        for (int digit = 0; digit < DIGIT_COUNT; digit++) {
            final var buckets = offsets[digit];

            for (int pos = 0; pos < length; pos++) {
                final var index = cur[pos];
                final var bucket_idx = extractDigit(keys[index], digit);

                next[buckets[bucket_idx]] = index;
                buckets[bucket_idx] += 1;
            }

            {
                // (cur, next) = (next, cur)
                var temp = next;
                next = cur;
                cur = temp;
            }
        }

        return cur;
    }

    private static int extractDigit(int key, int digit) {
        // Shift right by an extra 16 bits (ignore lowest 2 bytes of precision)
        return ((key >>> ((digit * DIGIT_BITS) + (32 - SIGNIFICANT_BITS))) & DIGIT_MASK);
    }

    public static boolean useRadixSort(int length) {
        return length >= RADIX_SORT_THRESHOLD;
    }
}