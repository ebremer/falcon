package com.ebremer.falcon.hdf5.filter;

import java.util.List;

/**
 * A dataset's filter pipeline (from Filter Pipeline message 11): the ordered list of filters applied
 * to each chunk on write. Decoding reverses that order.
 */
public final class FilterPipeline {

    /** One filter: its numeric id, flags, and client-data parameters. */
    public record Filter(int id, int flags, int[] clientData) {
    }

    private final List<Filter> filters;

    public FilterPipeline(List<Filter> filters) {
        this.filters = List.copyOf(filters);
    }

    public List<Filter> filters() {
        return filters;
    }

    /**
     * Decodes one chunk by applying the pipeline's filters in reverse, skipping any whose bit is set
     * in {@code filterMask}.
     */
    public byte[] decode(byte[] chunk, int filterMask, int elementSize) {
        byte[] data = chunk;
        for (int i = filters.size() - 1; i >= 0; i--) {
            if ((filterMask & (1 << i)) != 0) {
                continue;
            }
            data = Filters.decode(filters.get(i), data, elementSize);
        }
        return data;
    }
}
