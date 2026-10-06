package com.ebremer.falcon.core.compress.lz4;

/**
 * liblz4's high-compression compressor, {@code LZ4_compress_HC} of lz4 1.10.0's {@code lz4hc.c}, at its
 * levels 1 to 9, for one block with no dictionary. One instance compresses one block.
 *
 * <ul>
 *   <li>Levels 3 to 9 ({@code LZ4HC_compress_hashChain}, unchanged since lz4 1.9): every position is
 *       chained to the previous one with the same 4-byte hash (up to 64 KiB back), each search walks the
 *       chain for up to {@code 2^(level-1)} candidates, and up to three overlapping matches are weighed
 *       before one is written. Level 9 also recognizes runs of a repeated 1-, 2-, or 4-byte pattern
 *       ({@code patternAnalysis}).</li>
 *   <li>Levels 1 and 2 ({@code LZ4MID_compress}, new in lz4 1.10): one candidate from an 8-byte hash and
 *       one from a 4-byte hash per position, no chains. (lz4 1.9.4, which c-blosc 1.21 bundles, ran the
 *       hash chain with 2 candidates here; numcodecs 0.17 links lz4 1.10.0.)</li>
 * </ul>
 *
 * <p>Positions are kept as liblz4 keeps them, as indexes that start at 64 KiB, so an empty table entry
 * (0) is never a candidate.
 */
final class Lz4Hc {

    private static final int HASH_LOG = 15;
    private static final int MIN_MATCH = 4;
    /** {@code OPTIMAL_ML}: the longest match a token holds without a continuation byte. */
    private static final int OPTIMAL_ML = Lz4.ML_MASK - 1 + MIN_MATCH;
    /** The first index ({@code LZ4HC_init_internal} starts a new stream at 64 KiB). */
    private static final int PREFIX = 65536;
    /** LZ4MID's two tables, of 4- and 8-byte hashes, share the hash table, each half of it. */
    private static final int MID_HASH_LOG = HASH_LOG - 1;
    private static final int MID_TABLE_SIZE = 1 << MID_HASH_LOG;
    private static final int MID_HASH_SIZE = 8;

    private static final int REPEAT_UNTESTED = 0;
    private static final int REPEAT_NOT = 1;
    private static final int REPEAT_CONFIRMED = 2;

    private final byte[] src;
    private final int off;
    private final int len;
    private final int[] hashTable = new int[1 << HASH_LOG];
    private final char[] chainTable = new char[1 << 16]; // deltas back to the previous position, by index
    private int nextToUpdate = PREFIX;

    /** Set by a search that finds a longer match: where its earlier copy starts, and where it starts. */
    private int matchPos;
    private int startPos;

    // the output
    private byte[] dst;
    private int op;
    private int anchor;
    private int oend; // the end of the capacity, or -1 when unlimited

    Lz4Hc(byte[] src, int off, int len) {
        this.src = src;
        this.off = off;
        this.len = len;
    }

    /**
     * Compresses the block.
     *
     * @param oend  the end of {@code dst}'s capacity, or -1 for none
     * @param level 1 to 9
     * @return the block's length, or 0 if it does not fit
     */
    int compress(byte[] out, int dstOff, int oend, int level) {
        this.dst = out;
        this.op = dstOff;
        this.oend = oend;
        if (level <= 2) {
            return compressMid(dstOff);
        }
        int maxNbAttempts = 1 << (level - 1);
        boolean patternAnalysis = maxNbAttempts > 128; // level 9
        int ip = off;
        anchor = off;
        int iend = off + len;
        int mflimit = iend - Lz4.MFLIMIT;
        int matchlimit = iend - Lz4.LASTLITERALS;

        if (len >= Lz4.MIN_LENGTH) {
            main:
            while (ip <= mflimit) {
                int ml = widerMatch(ip, ip, matchlimit, MIN_MATCH - 1, maxNbAttempts, patternAnalysis);
                if (ml < MIN_MATCH) {
                    ip++;
                    continue;
                }
                int ref = matchPos;
                int start0 = ip;
                int ref0 = ref;
                int ml0 = ml;
                int ml2 = 0;
                int start2 = 0;
                int ref2 = 0;
                int ml3;
                int start3;
                int ref3;
                boolean search2 = true;
                while (true) {
                    if (search2) { // _Search2
                        if (ip + ml <= mflimit) {
                            int found = widerMatch(ip + ml - 2, ip, matchlimit, ml, maxNbAttempts, patternAnalysis);
                            if (found != ml) {
                                ref2 = matchPos;
                                start2 = startPos;
                            }
                            ml2 = found;
                        } else {
                            ml2 = ml;
                        }
                        if (ml2 == ml) { // no better match: encode ML1
                            if (encode(ip, ml, ref)) {
                                return 0;
                            }
                            ip = anchor;
                            continue main;
                        }
                        if (start0 < ip && start2 < ip + ml0) { // squeezing ML1 between ML0 and ML2
                            ip = start0;
                            ref = ref0;
                            ml = ml0;
                        }
                        if (start2 - ip < 3) { // the first match is too small: removed
                            ml = ml2;
                            ip = start2;
                            ref = ref2;
                            continue;
                        }
                        search2 = false;
                    }
                    // _Search3: ml2 > ml1, and ip1 + 3 <= ip2 (usually < ip1 + ml1)
                    if (start2 - ip < OPTIMAL_ML) {
                        int newMl = Math.min(ml, OPTIMAL_ML);
                        if (ip + newMl > start2 + ml2 - MIN_MATCH) {
                            newMl = start2 - ip + ml2 - MIN_MATCH;
                        }
                        int correction = newMl - (start2 - ip);
                        if (correction > 0) {
                            start2 += correction;
                            ref2 += correction;
                            ml2 -= correction;
                        }
                    }
                    if (start2 + ml2 <= mflimit) {
                        ml3 = widerMatch(start2 + ml2 - 3, start2, matchlimit, ml2, maxNbAttempts, patternAnalysis);
                        ref3 = matchPos;
                        start3 = startPos;
                    } else {
                        ml3 = ml2;
                        ref3 = 0;
                        start3 = 0;
                    }
                    if (ml3 == ml2) { // no better match: encode ML1 and ML2
                        if (start2 < ip + ml) {
                            ml = start2 - ip;
                        }
                        if (encode(ip, ml, ref)) {
                            return 0;
                        }
                        ip = start2;
                        if (encode(ip, ml2, ref2)) {
                            return 0;
                        }
                        ip = anchor;
                        continue main;
                    }
                    if (start3 < ip + ml + 3) { // not enough space for match 2: remove it
                        if (start3 >= ip + ml) { // Seq1 can be written now; Seq3 becomes Seq1
                            if (start2 < ip + ml) {
                                int correction = ip + ml - start2;
                                start2 += correction;
                                ref2 += correction;
                                ml2 -= correction;
                                if (ml2 < MIN_MATCH) {
                                    start2 = start3;
                                    ref2 = ref3;
                                    ml2 = ml3;
                                }
                            }
                            if (encode(ip, ml, ref)) {
                                return 0;
                            }
                            ip = start3;
                            ref = ref3;
                            ml = ml3;
                            start0 = start2;
                            ref0 = ref2;
                            ml0 = ml2;
                            search2 = true;
                            continue;
                        }
                        start2 = start3;
                        ref2 = ref3;
                        ml2 = ml3;
                        continue; // _Search3
                    }
                    // three ascending matches: write the first, deciding its length
                    if (start2 < ip + ml) {
                        if (start2 - ip < OPTIMAL_ML) {
                            if (ml > OPTIMAL_ML) {
                                ml = OPTIMAL_ML;
                            }
                            if (ip + ml > start2 + ml2 - MIN_MATCH) {
                                ml = start2 - ip + ml2 - MIN_MATCH;
                            }
                            int correction = ml - (start2 - ip);
                            if (correction > 0) {
                                start2 += correction;
                                ref2 += correction;
                                ml2 -= correction;
                            }
                        } else {
                            ml = start2 - ip;
                        }
                    }
                    if (encode(ip, ml, ref)) {
                        return 0;
                    }
                    ip = start2; // ML2 becomes ML1
                    ref = ref2;
                    ml = ml2;
                    start2 = start3; // ML3 becomes ML2
                    ref2 = ref3;
                    ml2 = ml3;
                    // and a new ML3 is searched for (_Search3)
                }
            }
        }
        int end = Lz4.lastLiterals(src, anchor, iend - anchor, dst, op, oend);
        return end < 0 ? 0 : end - dstOff;
    }

    /**
     * {@code LZ4MID_compress} (levels 1 and 2): at each position, a match from the 8-byte hash table, else
     * one from the 4-byte table, which a longer match one byte on may replace. A match is extended back
     * over equal bytes, and the tables are refilled around both of its ends; without a match the scan
     * skips faster the longer it has gone without one.
     */
    private int compressMid(int dstOff) {
        int ip = off;
        anchor = off;
        int iend = off + len;
        int mflimit = iend - Lz4.MFLIMIT;
        int matchlimit = iend - Lz4.LASTLITERALS;
        int ilimitIdx = iend - MID_HASH_SIZE - off + PREFIX;
        int base8 = MID_TABLE_SIZE; // the 4-byte table is the first half, the 8-byte table the second

        if (len >= Lz4.MIN_LENGTH) {
            while (ip <= mflimit) {
                int ipIndex = ip - off + PREFIX;
                int matchLength = 0;
                int matchDistance = 0;
                int h8 = midHash8(ip);
                int pos8 = hashTable[base8 + h8];
                hashTable[base8 + h8] = ipIndex;
                if (Integer.compareUnsigned(ipIndex - pos8, Lz4.MAX_DISTANCE) <= 0 && pos8 >= PREFIX) { // a long match
                    int m = Lz4.count(src, ip, off + pos8 - PREFIX, matchlimit);
                    if (m >= MIN_MATCH) {
                        matchLength = m;
                        matchDistance = ipIndex - pos8;
                    }
                }
                if (matchLength == 0) { // a short match
                    int h4 = midHash4(ip);
                    int pos4 = hashTable[h4];
                    hashTable[h4] = ipIndex;
                    if (Integer.compareUnsigned(ipIndex - pos4, Lz4.MAX_DISTANCE) <= 0 && pos4 >= PREFIX) {
                        int m = Lz4.count(src, ip, off + pos4 - PREFIX, matchlimit);
                        if (m >= MIN_MATCH) {
                            matchLength = m;
                            matchDistance = ipIndex - pos4;
                            // is there a longer match at ip + 1?
                            int h8b = midHash8(ip + 1);
                            int pos8b = hashTable[base8 + h8b];
                            int m2Distance = ipIndex + 1 - pos8b;
                            if (Integer.compareUnsigned(m2Distance, Lz4.MAX_DISTANCE) <= 0 && pos8b >= PREFIX && ip < mflimit) {
                                int ml2 = Lz4.count(src, ip + 1, off + pos8b - PREFIX, matchlimit);
                                if (ml2 > matchLength) {
                                    hashTable[base8 + h8b] = ipIndex + 1;
                                    ip++;
                                    matchLength = ml2;
                                    matchDistance = m2Distance;
                                }
                            }
                        }
                    }
                }
                if (matchLength == 0) {
                    ip += 1 + ((ip - anchor) >> 9); // skip faster over incompressible data
                    continue;
                }
                while (ip > anchor && ip - off > matchDistance && src[ip - 1] == src[ip - matchDistance - 1]) {
                    ip--; // catch back
                    matchLength++;
                }
                // fill the tables at the match's beginning (with the indexes liblz4 uses, from before catching back)
                hashTable[base8 + midHash8(ip + 1)] = ipIndex + 1;
                hashTable[base8 + midHash8(ip + 2)] = ipIndex + 2;
                hashTable[midHash4(ip + 1)] = ipIndex + 1;
                if (encodeOffset(ip, matchLength, matchDistance)) {
                    return 0;
                }
                ip = anchor;
                int endMatchIdx = ip - off + PREFIX; // and at its end
                if (endMatchIdx - 2 < ilimitIdx) {
                    if (ip - off > 5) {
                        hashTable[base8 + midHash8(ip - 5)] = endMatchIdx - 5;
                    }
                    hashTable[base8 + midHash8(ip - 3)] = endMatchIdx - 3;
                    hashTable[base8 + midHash8(ip - 2)] = endMatchIdx - 2;
                    hashTable[midHash4(ip - 2)] = endMatchIdx - 2;
                    hashTable[midHash4(ip - 1)] = endMatchIdx - 1;
                }
            }
        }
        int end = Lz4.lastLiterals(src, anchor, iend - anchor, dst, op, oend);
        return end < 0 ? 0 : end - dstOff;
    }

    /** {@code LZ4MID_hash4Ptr}. */
    private int midHash4(int p) {
        return (Lz4.read32(src, p) * -1640531535) >>> (32 - MID_HASH_LOG); // 2654435761U
    }

    /** {@code LZ4MID_hash8Ptr}: 7 bytes of a little-endian 64-bit read. */
    private int midHash8(int p) {
        long v = (Lz4.read32(src, p) & 0xffffffffL) | (long) Lz4.read32(src, p + 4) << 32;
        return (int) (((v << 8) * 58295818150454627L) >>> (64 - MID_HASH_LOG));
    }

    /**
     * {@code LZ4HC_encodeSequence}: the literals from the anchor to {@code ip}, then a match of
     * {@code matchLength} bytes at {@code match}. Leaves the anchor after the match.
     *
     * @return true if the sequence does not fit (the block then fails)
     */
    private boolean encode(int ip, int matchLength, int match) {
        return encodeOffset(ip, matchLength, ip - match);
    }

    /** {@link #encode} with the match given by its offset back from {@code ip}. */
    private boolean encodeOffset(int ip, int matchLength, int offset) {
        int token = op++;
        int length = ip - anchor;
        if (oend >= 0 && (long) op + length / 255 + length + (2 + 1 + Lz4.LASTLITERALS) > oend) {
            return true;
        }
        op = Lz4.writeLiteralLength(dst, token, op, length);
        System.arraycopy(src, anchor, dst, op, length);
        op += length;
        dst[op] = (byte) offset;
        dst[op + 1] = (byte) (offset >>> 8);
        op += 2;
        int code = matchLength - MIN_MATCH;
        if (oend >= 0 && (long) op + code / 255 + (1 + Lz4.LASTLITERALS) > oend) {
            return true;
        }
        op = Lz4.writeMatchLength(dst, token, op, code);
        anchor = ip + matchLength;
        return false;
    }

    private int hashPtr(int p) {
        return (Lz4.read32(src, p) * -1640531535) >>> (32 - HASH_LOG); // 2654435761U
    }

    /** {@code LZ4HC_Insert}: chains every position before {@code ip} not yet chained. */
    private void insert(int ip) {
        int target = ip - off + PREFIX;
        for (int idx = nextToUpdate; idx < target; idx++) {
            int h = hashPtr(off + idx - PREFIX);
            long delta = (idx - hashTable[h]) & 0xffffffffL; // unsigned, as liblz4's
            chainTable[idx & 0xFFFF] = (char) Math.min(delta, Lz4.MAX_DISTANCE);
            hashTable[h] = idx;
        }
        nextToUpdate = target;
    }

    /**
     * {@code LZ4HC_InsertAndGetWiderMatch} without a dictionary, chain swapping, or the bias toward
     * decompression speed: the longest match at {@code ip} that can also extend back as far as
     * {@code iLowLimit}, if longer than {@code longest}. A longer one is left in {@link #matchPos} and
     * {@link #startPos}.
     *
     * @return the longest length found ({@code longest} if none is longer)
     */
    private int widerMatch(int ip, int iLowLimit, int iHighLimit, int longest, int maxNbAttempts,
                           boolean patternAnalysis) {
        int ipIndex = ip - off + PREFIX;
        boolean withinStartDistance = PREFIX + (Lz4.MAX_DISTANCE + 1) > ipIndex;
        int lowestMatchIndex = withinStartDistance ? PREFIX : ipIndex - Lz4.MAX_DISTANCE;
        int lookBackLength = ip - iLowLimit;
        int nbAttempts = maxNbAttempts;
        int pattern = Lz4.read32(src, ip);
        int repeat = REPEAT_UNTESTED;
        long srcPatternLength = 0;

        insert(ip);
        int matchIndex = hashTable[hashPtr(ip)];
        while (matchIndex >= lowestMatchIndex && nbAttempts > 0) {
            nbAttempts--;
            int matchPtr = off + matchIndex - PREFIX;
            if (read16(iLowLimit + longest - 1) == read16(matchPtr - lookBackLength + longest - 1)
                    && Lz4.read32(src, matchPtr) == pattern) {
                int back = lookBackLength != 0 ? countBack(ip, matchPtr, iLowLimit, off) : 0;
                int matchLength = MIN_MATCH + Lz4.count(src, ip + MIN_MATCH, matchPtr + MIN_MATCH, iHighLimit) - back;
                if (matchLength > longest) {
                    longest = matchLength;
                    matchPos = matchPtr + back;
                    startPos = ip + back;
                }
            }

            int distNextMatch = chainTable[matchIndex & 0xFFFF];
            if (patternAnalysis && distNextMatch == 1) {
                int matchCandidateIdx = matchIndex - 1;
                if (repeat == REPEAT_UNTESTED) { // is it a run of a 1-, 2-, or 4-byte pattern?
                    if (((pattern & 0xFFFF) == (pattern >>> 16)) & ((pattern & 0xFF) == (pattern >>> 24))) {
                        repeat = REPEAT_CONFIRMED;
                        srcPatternLength = countPattern(ip + 4, iHighLimit, pattern) + 4;
                    } else {
                        repeat = REPEAT_NOT;
                    }
                }
                if (repeat == REPEAT_CONFIRMED && matchCandidateIdx >= lowestMatchIndex) {
                    int candidatePtr = off + matchCandidateIdx - PREFIX;
                    if (Lz4.read32(src, candidatePtr) == pattern) { // a good candidate
                        long forwardPatternLength = countPattern(candidatePtr + 4, iHighLimit, pattern) + 4;
                        long backLength = reverseCountPattern(candidatePtr, off, pattern);
                        // limit backLength so it goes no further than lowestMatchIndex
                        backLength = matchCandidateIdx - Math.max(matchCandidateIdx - backLength, lowestMatchIndex);
                        long currentSegmentLength = backLength + forwardPatternLength;
                        if (currentSegmentLength >= srcPatternLength && forwardPatternLength <= srcPatternLength) {
                            // the end of the pattern, if the source pattern fits; might be followed by more
                            matchIndex = (int) (matchCandidateIdx + forwardPatternLength - srcPatternLength);
                        } else {
                            // the farthest position in the current segment
                            matchIndex = (int) (matchCandidateIdx - backLength);
                            if (lookBackLength == 0) {
                                long maxMl = Math.min(currentSegmentLength, srcPatternLength);
                                if (longest < maxMl) {
                                    if ((long) ip - off + PREFIX - matchIndex > Lz4.MAX_DISTANCE) {
                                        break;
                                    }
                                    longest = (int) maxMl;
                                    matchPos = off + matchIndex - PREFIX;
                                    startPos = ip;
                                }
                                int distToNextPattern = chainTable[matchIndex & 0xFFFF];
                                if (distToNextPattern > matchIndex) {
                                    break;
                                }
                                matchIndex -= distToNextPattern;
                            }
                        }
                        continue;
                    }
                }
            }
            matchIndex -= chainTable[matchIndex & 0xFFFF]; // follow the chain
        }
        return longest;
    }

    private int read16(int p) {
        return (src[p] & 0xff) | (src[p + 1] & 0xff) << 8;
    }

    /** {@code LZ4HC_countBack}: minus the number of equal bytes before {@code ip} and {@code match}. */
    private int countBack(int ip, int match, int iMin, int mMin) {
        int back = 0;
        int min = Math.max(iMin - ip, mMin - match);
        while (back > min && src[ip + back - 1] == src[match + back - 1]) {
            back--;
        }
        return back;
    }

    /** {@code LZ4HC_countPattern}: how many bytes from {@code ip} repeat the 4-byte {@code pattern}. */
    private long countPattern(int ip, int iEnd, int pattern) {
        int i = ip;
        while (i < iEnd && src[i] == (byte) (pattern >>> (8 * ((i - ip) & 3)))) {
            i++;
        }
        return i - ip;
    }

    /** {@code LZ4HC_reverseCountPattern}: how many bytes before {@code ip} end with the pattern. */
    private long reverseCountPattern(int ip, int iLow, int pattern) {
        int i = ip;
        while (i > iLow && src[i - 1] == (byte) (pattern >>> (8 * (3 - ((ip - i) & 3))))) {
            i--;
        }
        return ip - i;
    }
}
