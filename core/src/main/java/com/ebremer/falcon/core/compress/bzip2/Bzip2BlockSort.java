package com.ebremer.falcon.core.compress.bzip2;

/**
 * The Burrows&ndash;Wheeler sort of one block, a port of libbzip2 1.0.8's {@code blocksort.c}: for a block of
 * under 10000 bytes, or one too repetitive for the main sort's work budget, the fallback exponential radix
 * sort ({@code fallbackSort}); otherwise the main sort, a radix sort on two bytes then a three-way string
 * quicksort ({@code mainSort}, {@code mainQSort3}, {@code mainSimpleSort}, {@code mainGtU}).
 *
 * <p>Ported step for step: where rotations are equal (a periodic block), the order libbzip2 leaves them in
 * decides the origin pointer it writes, so only the same algorithm writes the same stream.
 */
final class Bzip2BlockSort {

    /** Bytes of the block copied after its end, so comparisons may run past it ({@code BZ_N_OVERSHOOT}). */
    static final int OVERSHOOT = 2 + 12 + 18 + 2;

    private static final int N_RADIX = 2;
    private static final int N_QSORT = 12;
    private static final int FALLBACK_QSORT_SMALL_THRESH = 10;
    private static final int FALLBACK_QSORT_STACK_SIZE = 100;
    private static final int MAIN_QSORT_SMALL_THRESH = 20;
    private static final int MAIN_QSORT_DEPTH_THRESH = N_RADIX + N_QSORT;
    private static final int MAIN_QSORT_STACK_SIZE = 100;
    private static final int SETMASK = 1 << 21;
    private static final int CLEARMASK = ~SETMASK;

    /** Knuth's increments, for the shell sort of small buckets. */
    private static final int[] INCS = {1, 4, 13, 40, 121, 364, 1093, 3280, 9841, 29524, 88573, 265720, 797161,
        2391484};

    private final byte[] block;
    private final int[] ptr;
    private final int nblock;
    private int budget;

    private Bzip2BlockSort(byte[] block, int[] ptr, int nblock) {
        this.block = block;
        this.ptr = ptr;
        this.nblock = nblock;
    }

    /**
     * Sorts the rotations of {@code block[0, nblock)} into {@code ptr} ({@code BZ2_blockSort} with the
     * default work factor, 30) and returns the origin pointer: the position of the unrotated block.
     *
     * @param block the block, with room for {@link #OVERSHOOT} more bytes after it (overwritten)
     */
    static int sort(byte[] block, int[] ptr, int nblock) {
        Bzip2BlockSort s = new Bzip2BlockSort(block, ptr, nblock);
        if (nblock < 10000) {
            s.fallbackSort();
        } else {
            int workFactor = 30;
            s.budget = nblock * ((workFactor - 1) / 3);
            s.mainSort(new char[nblock + OVERSHOOT]);
            if (s.budget < 0) { // too repetitive: start over with the fallback sort
                s.fallbackSort();
            }
        }
        for (int i = 0; i < nblock; i++) {
            if (ptr[i] == 0) {
                return i;
            }
        }
        throw new IllegalStateException("bzip2 block sort lost the origin");
    }

    // ------------------------------------------------------------------ fallback sort

    private static void fallbackSimpleSort(int[] fmap, int[] eclass, int lo, int hi) {
        if (lo == hi) {
            return;
        }
        if (hi - lo > 3) {
            for (int i = hi - 4; i >= lo; i--) {
                int tmp = fmap[i];
                int ecTmp = eclass[tmp];
                int j;
                for (j = i + 4; j <= hi && ecTmp > eclass[fmap[j]]; j += 4) {
                    fmap[j - 4] = fmap[j];
                }
                fmap[j - 4] = tmp;
            }
        }
        for (int i = hi - 1; i >= lo; i--) {
            int tmp = fmap[i];
            int ecTmp = eclass[tmp];
            int j;
            for (j = i + 1; j <= hi && ecTmp > eclass[fmap[j]]; j++) {
                fmap[j - 1] = fmap[j];
            }
            fmap[j - 1] = tmp;
        }
    }

    private static void fallbackQSort3(int[] fmap, int[] eclass, int loSt, int hiSt) {
        int[] stackLo = new int[FALLBACK_QSORT_STACK_SIZE];
        int[] stackHi = new int[FALLBACK_QSORT_STACK_SIZE];
        int r = 0;
        int sp = 0;
        stackLo[sp] = loSt;
        stackHi[sp] = hiSt;
        sp++;
        while (sp > 0) {
            sp--;
            int lo = stackLo[sp];
            int hi = stackHi[sp];
            if (hi - lo < FALLBACK_QSORT_SMALL_THRESH) {
                fallbackSimpleSort(fmap, eclass, lo, hi);
                continue;
            }
            // Random partitioning (Sedgewick's constants), as libbzip2 does.
            r = ((r * 7621) + 1) % 32768;
            int r3 = r % 3;
            int med = r3 == 0 ? eclass[fmap[lo]] : r3 == 1 ? eclass[fmap[(lo + hi) >> 1]] : eclass[fmap[hi]];
            int unLo = lo;
            int ltLo = lo;
            int unHi = hi;
            int gtHi = hi;
            while (true) {
                while (unLo <= unHi) {
                    int n = eclass[fmap[unLo]] - med;
                    if (n == 0) {
                        swap(fmap, unLo, ltLo);
                        ltLo++;
                        unLo++;
                        continue;
                    }
                    if (n > 0) {
                        break;
                    }
                    unLo++;
                }
                while (unLo <= unHi) {
                    int n = eclass[fmap[unHi]] - med;
                    if (n == 0) {
                        swap(fmap, unHi, gtHi);
                        gtHi--;
                        unHi--;
                        continue;
                    }
                    if (n < 0) {
                        break;
                    }
                    unHi--;
                }
                if (unLo > unHi) {
                    break;
                }
                swap(fmap, unLo, unHi);
                unLo++;
                unHi--;
            }
            if (gtHi < ltLo) {
                continue;
            }
            int n = Math.min(ltLo - lo, unLo - ltLo);
            vswap(fmap, lo, unLo - n, n);
            int m = Math.min(hi - gtHi, gtHi - unHi);
            vswap(fmap, unLo, hi - m + 1, m);
            n = lo + unLo - ltLo - 1;
            m = hi - (gtHi - unHi) + 1;
            if (n - lo > hi - m) {
                stackLo[sp] = lo;
                stackHi[sp] = n;
                sp++;
                stackLo[sp] = m;
                stackHi[sp] = hi;
                sp++;
            } else {
                stackLo[sp] = m;
                stackHi[sp] = hi;
                sp++;
                stackLo[sp] = lo;
                stackHi[sp] = n;
                sp++;
            }
        }
    }

    private static boolean isSet(int[] bhtab, int z) {
        return (bhtab[z >> 5] & (1 << (z & 31))) != 0;
    }

    private static void set(int[] bhtab, int z) {
        bhtab[z >> 5] |= 1 << (z & 31);
    }

    private static void clear(int[] bhtab, int z) {
        bhtab[z >> 5] &= ~(1 << (z & 31));
    }

    /** {@code fallbackSort}: Manber&ndash;Myers-like doubling over bucket header bits. */
    private void fallbackSort() {
        int[] fmap = ptr;
        int[] eclass = new int[nblock];
        int[] bhtab = new int[2 + nblock / 32 + 1];
        int[] ftab = new int[257];
        for (int i = 0; i < nblock; i++) {
            ftab[block[i] & 0xff]++;
        }
        for (int i = 1; i < 257; i++) {
            ftab[i] += ftab[i - 1];
        }
        for (int i = 0; i < nblock; i++) {
            int j = block[i] & 0xff;
            int k = ftab[j] - 1;
            ftab[j] = k;
            fmap[k] = i;
        }
        for (int i = 0; i < 256; i++) {
            set(bhtab, ftab[i]);
        }
        // Sentinel bits for block-end detection.
        for (int i = 0; i < 32; i++) {
            set(bhtab, nblock + 2 * i);
            clear(bhtab, nblock + 2 * i + 1);
        }
        int h = 1;
        while (true) {
            int j = 0;
            for (int i = 0; i < nblock; i++) {
                if (isSet(bhtab, i)) {
                    j = i;
                }
                int k = fmap[i] - h;
                if (k < 0) {
                    k += nblock;
                }
                eclass[k] = j;
            }
            int notDone = 0;
            int r = -1;
            while (true) {
                // Find the next non-singleton bucket.
                int k = r + 1;
                while (isSet(bhtab, k) && (k & 0x1f) != 0) {
                    k++;
                }
                if (isSet(bhtab, k)) {
                    while (bhtab[k >> 5] == 0xffffffff) {
                        k += 32;
                    }
                    while (isSet(bhtab, k)) {
                        k++;
                    }
                }
                int l = k - 1;
                if (l >= nblock) {
                    break;
                }
                while (!isSet(bhtab, k) && (k & 0x1f) != 0) {
                    k++;
                }
                if (!isSet(bhtab, k)) {
                    while (bhtab[k >> 5] == 0) {
                        k += 32;
                    }
                    while (!isSet(bhtab, k)) {
                        k++;
                    }
                }
                r = k - 1;
                if (r >= nblock) {
                    break;
                }
                // [l, r] bracket the current bucket.
                if (r > l) {
                    notDone += r - l + 1;
                    fallbackQSort3(fmap, eclass, l, r);
                    int cc = -1;
                    for (int i = l; i <= r; i++) {
                        int cc1 = eclass[fmap[i]];
                        if (cc != cc1) {
                            set(bhtab, i);
                            cc = cc1;
                        }
                    }
                }
            }
            h *= 2;
            if (h > nblock || notDone == 0) {
                break;
            }
        }
    }

    // ------------------------------------------------------------------ main sort

    private boolean mainGtU(int i1, int i2, char[] quadrant) {
        for (int n = 0; n < 12; n++) {
            int c1 = block[i1] & 0xff;
            int c2 = block[i2] & 0xff;
            if (c1 != c2) {
                return c1 > c2;
            }
            i1++;
            i2++;
        }
        int k = nblock + 8;
        do {
            for (int n = 0; n < 8; n++) {
                int c1 = block[i1] & 0xff;
                int c2 = block[i2] & 0xff;
                if (c1 != c2) {
                    return c1 > c2;
                }
                int s1 = quadrant[i1];
                int s2 = quadrant[i2];
                if (s1 != s2) {
                    return s1 > s2;
                }
                i1++;
                i2++;
            }
            if (i1 >= nblock) {
                i1 -= nblock;
            }
            if (i2 >= nblock) {
                i2 -= nblock;
            }
            k -= 8;
            budget--;
        } while (k >= 0);
        return false;
    }

    private void mainSimpleSort(char[] quadrant, int lo, int hi, int d) {
        int bigN = hi - lo + 1;
        if (bigN < 2) {
            return;
        }
        int hp = 0;
        while (INCS[hp] < bigN) {
            hp++;
        }
        hp--;
        for (; hp >= 0; hp--) {
            int h = INCS[hp];
            int i = lo + h;
            // libbzip2 unrolls this loop three times, checking the budget after each three insertions.
            boolean done = false;
            while (!done) {
                for (int copy = 0; copy < 3; copy++) {
                    if (i > hi) {
                        done = true;
                        break;
                    }
                    int v = ptr[i];
                    int j = i;
                    while (mainGtU(ptr[j - h] + d, v + d, quadrant)) {
                        ptr[j] = ptr[j - h];
                        j = j - h;
                        if (j <= lo + h - 1) {
                            break;
                        }
                    }
                    ptr[j] = v;
                    i++;
                }
                if (!done && budget < 0) {
                    return;
                }
            }
        }
    }

    private static int med3(int a, int b, int c) {
        if (a > b) {
            int t = a;
            a = b;
            b = t;
        }
        if (b > c) {
            b = c;
            if (a > b) {
                b = a;
            }
        }
        return b;
    }

    private void mainQSort3(char[] quadrant, int loSt, int hiSt, int dSt) {
        int[] stackLo = new int[MAIN_QSORT_STACK_SIZE];
        int[] stackHi = new int[MAIN_QSORT_STACK_SIZE];
        int[] stackD = new int[MAIN_QSORT_STACK_SIZE];
        int[] nextLo = new int[3];
        int[] nextHi = new int[3];
        int[] nextD = new int[3];
        int sp = 0;
        stackLo[sp] = loSt;
        stackHi[sp] = hiSt;
        stackD[sp] = dSt;
        sp++;
        while (sp > 0) {
            sp--;
            int lo = stackLo[sp];
            int hi = stackHi[sp];
            int d = stackD[sp];
            if (hi - lo < MAIN_QSORT_SMALL_THRESH || d > MAIN_QSORT_DEPTH_THRESH) {
                mainSimpleSort(quadrant, lo, hi, d);
                if (budget < 0) {
                    return;
                }
                continue;
            }
            int med = med3(block[ptr[lo] + d] & 0xff, block[ptr[hi] + d] & 0xff, block[ptr[(lo + hi) >> 1] + d] & 0xff);
            int unLo = lo;
            int ltLo = lo;
            int unHi = hi;
            int gtHi = hi;
            while (true) {
                while (unLo <= unHi) {
                    int n = (block[ptr[unLo] + d] & 0xff) - med;
                    if (n == 0) {
                        swap(ptr, unLo, ltLo);
                        ltLo++;
                        unLo++;
                        continue;
                    }
                    if (n > 0) {
                        break;
                    }
                    unLo++;
                }
                while (unLo <= unHi) {
                    int n = (block[ptr[unHi] + d] & 0xff) - med;
                    if (n == 0) {
                        swap(ptr, unHi, gtHi);
                        gtHi--;
                        unHi--;
                        continue;
                    }
                    if (n < 0) {
                        break;
                    }
                    unHi--;
                }
                if (unLo > unHi) {
                    break;
                }
                swap(ptr, unLo, unHi);
                unLo++;
                unHi--;
            }
            if (gtHi < ltLo) {
                stackLo[sp] = lo;
                stackHi[sp] = hi;
                stackD[sp] = d + 1;
                sp++;
                continue;
            }
            int n = Math.min(ltLo - lo, unLo - ltLo);
            vswap(ptr, lo, unLo - n, n);
            int m = Math.min(hi - gtHi, gtHi - unHi);
            vswap(ptr, unLo, hi - m + 1, m);
            n = lo + unLo - ltLo - 1;
            m = hi - (gtHi - unHi) + 1;
            nextLo[0] = lo;
            nextHi[0] = n;
            nextD[0] = d;
            nextLo[1] = m;
            nextHi[1] = hi;
            nextD[1] = d;
            nextLo[2] = n + 1;
            nextHi[2] = m - 1;
            nextD[2] = d + 1;
            if (nextHi[0] - nextLo[0] < nextHi[1] - nextLo[1]) {
                nextSwap(nextLo, nextHi, nextD, 0, 1);
            }
            if (nextHi[1] - nextLo[1] < nextHi[2] - nextLo[2]) {
                nextSwap(nextLo, nextHi, nextD, 1, 2);
            }
            if (nextHi[0] - nextLo[0] < nextHi[1] - nextLo[1]) {
                nextSwap(nextLo, nextHi, nextD, 0, 1);
            }
            for (int z = 0; z < 3; z++) {
                stackLo[sp] = nextLo[z];
                stackHi[sp] = nextHi[z];
                stackD[sp] = nextD[z];
                sp++;
            }
        }
    }

    private static void nextSwap(int[] lo, int[] hi, int[] d, int a, int b) {
        swap(lo, a, b);
        swap(hi, a, b);
        swap(d, a, b);
    }

    private static int bigFreq(int[] ftab, int b) {
        return ftab[(b + 1) << 8] - ftab[b << 8];
    }

    /** {@code mainSort}; leaves {@code budget} negative if it gave up. */
    private void mainSort(char[] quadrant) {
        int[] ftab = new int[65537];
        int[] runningOrder = new int[256];
        boolean[] bigDone = new boolean[256];
        int[] copyStart = new int[256];
        int[] copyEnd = new int[256];

        // The 2-byte frequency table.
        int j = (block[0] & 0xff) << 8;
        for (int i = nblock - 1; i >= 0; i--) {
            quadrant[i] = 0;
            j = (j >> 8) | ((block[i] & 0xff) << 8);
            ftab[j]++;
        }
        for (int i = 0; i < OVERSHOOT; i++) {
            block[nblock + i] = block[i];
            quadrant[nblock + i] = 0;
        }

        // Complete the initial radix sort.
        for (int i = 1; i <= 65536; i++) {
            ftab[i] += ftab[i - 1];
        }
        int s = (block[0] & 0xff) << 8;
        for (int i = nblock - 1; i >= 0; i--) {
            s = ((s >> 8) | ((block[i] & 0xff) << 8)) & 0xffff;
            j = ftab[s] - 1;
            ftab[s] = j;
            ptr[j] = i;
        }

        // The running order, from the smallest big bucket to the largest.
        for (int i = 0; i <= 255; i++) {
            runningOrder[i] = i;
        }
        int h = 1;
        do {
            h = 3 * h + 1;
        } while (h <= 256);
        do {
            h = h / 3;
            for (int i = h; i <= 255; i++) {
                int vv = runningOrder[i];
                j = i;
                while (bigFreq(ftab, runningOrder[j - h]) > bigFreq(ftab, vv)) {
                    runningOrder[j] = runningOrder[j - h];
                    j = j - h;
                    if (j <= h - 1) {
                        break;
                    }
                }
                runningOrder[j] = vv;
            }
        } while (h != 1);

        // The main sorting loop.
        for (int i = 0; i <= 255; i++) {
            int ss = runningOrder[i];

            // Step 1: quicksort the small buckets [ss, j] not yet sorted.
            for (j = 0; j <= 255; j++) {
                if (j != ss) {
                    int sb = (ss << 8) + j;
                    if ((ftab[sb] & SETMASK) == 0) {
                        int lo = ftab[sb] & CLEARMASK;
                        int hi = (ftab[sb + 1] & CLEARMASK) - 1;
                        if (hi > lo) {
                            mainQSort3(quadrant, lo, hi, N_RADIX);
                            if (budget < 0) {
                                return;
                            }
                        }
                    }
                    ftab[sb] |= SETMASK;
                }
            }

            // Step 2: scan big bucket [ss] to order the small buckets [t, ss], for every t.
            for (j = 0; j <= 255; j++) {
                copyStart[j] = ftab[(j << 8) + ss] & CLEARMASK;
                copyEnd[j] = (ftab[(j << 8) + ss + 1] & CLEARMASK) - 1;
            }
            for (j = ftab[ss << 8] & CLEARMASK; j < copyStart[ss]; j++) {
                int k = ptr[j] - 1;
                if (k < 0) {
                    k += nblock;
                }
                int c1 = block[k] & 0xff;
                if (!bigDone[c1]) {
                    ptr[copyStart[c1]++] = k;
                }
            }
            for (j = (ftab[(ss + 1) << 8] & CLEARMASK) - 1; j > copyEnd[ss]; j--) {
                int k = ptr[j] - 1;
                if (k < 0) {
                    k += nblock;
                }
                int c1 = block[k] & 0xff;
                if (!bigDone[c1]) {
                    ptr[copyEnd[c1]--] = k;
                }
            }
            for (j = 0; j <= 255; j++) {
                ftab[(j << 8) + ss] |= SETMASK;
            }

            // Step 3: big bucket [ss] is done; record its order in the quadrant descriptors.
            bigDone[ss] = true;
            if (i < 255) {
                int bbStart = ftab[ss << 8] & CLEARMASK;
                int bbSize = (ftab[(ss + 1) << 8] & CLEARMASK) - bbStart;
                int shifts = 0;
                while ((bbSize >> shifts) > 65534) {
                    shifts++;
                }
                for (j = bbSize - 1; j >= 0; j--) {
                    int a2update = ptr[bbStart + j];
                    char qVal = (char) (j >> shifts);
                    quadrant[a2update] = qVal;
                    if (a2update < OVERSHOOT) {
                        quadrant[a2update + nblock] = qVal;
                    }
                }
            }
        }
    }

    private static void swap(int[] a, int i, int j) {
        int t = a[i];
        a[i] = a[j];
        a[j] = t;
    }

    private static void vswap(int[] a, int p1, int p2, int n) {
        while (n > 0) {
            swap(a, p1, p2);
            p1++;
            p2++;
            n--;
        }
    }
}
